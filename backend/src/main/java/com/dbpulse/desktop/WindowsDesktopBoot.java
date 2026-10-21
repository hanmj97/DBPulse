package com.dbpulse.desktop;

import com.sun.jna.platform.win32.Crypt32Util;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.MapPropertySource;

/**
 * Windows native app-image bootstrap only. Dev/Docker Java launch stays intact.
 * The PostgreSQL metadata secret is a separate user-scoped DPAPI blob, never
 * checked into Git or stored in app-image files. No Docker daemon privilege,
 * shell command, or SQL credentials are exposed to the browser.
 */
public final class WindowsDesktopBoot {
    private static final String SECRET_FILE = "metadata.dpapi";
    private static final byte[] HEADER = "DBPM1".getBytes(StandardCharsets.US_ASCII);
    private WindowsDesktopBoot() {}

    private static Path directory() {
        if (!System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new IllegalStateException("DESKTOP_WINDOWS_REQUIRED");
        }
        String user=System.getProperty("user.name","");
        if (user.equalsIgnoreCase("SYSTEM") || user.equalsIgnoreCase("LOCAL SERVICE")
            || user.equalsIgnoreCase("NETWORK SERVICE")) {
            throw new IllegalStateException("DESKTOP_INTERACTIVE_USER_REQUIRED");
        }
        String appdata=System.getenv("LOCALAPPDATA");
        if (appdata==null || appdata.isBlank()) {
            throw new IllegalStateException("DESKTOP_LOCALAPPDATA_MISSING");
        }
        return Path.of(appdata, "DBPulse", "desktop");
    }

    /**
     * One-time interactive provisioning with a secret supplied as a temporary
     * process environment variable. The .exe never has to read a .env file.
     */
    public static void provision() {
        String password=System.getenv("DBPULSE_META_PASSWORD");
        if (password==null || password.isBlank() || password.length()>512) {
            throw new IllegalStateException("DESKTOP_METADATA_PASSWORD_REQUIRED");
        }
        Path dir=directory();
        byte[] secret=password.getBytes(StandardCharsets.UTF_8);
        byte[] sealed;
        try {
            sealed=Crypt32Util.cryptProtectData(secret);
        } finally {
            Arrays.fill(secret,(byte)0);
        }
        Path tmp=null;
        try {
            Files.createDirectories(dir);
            if (Files.isSymbolicLink(dir)) throw new IOException("UNSAFE_CONFIG_DIR");
            Path target=dir.resolve(SECRET_FILE);
            if (Files.isSymbolicLink(target)) throw new IOException("UNSAFE_CONFIG_FILE");
            tmp=Files.createTempFile(dir, "metadata-", ".tmp");
            byte[] wrapped=new byte[HEADER.length+sealed.length];
            try {
                System.arraycopy(HEADER,0,wrapped,0,HEADER.length);
                System.arraycopy(sealed,0,wrapped,HEADER.length,sealed.length);
                Files.write(tmp,wrapped);
            } finally {
                Arrays.fill(wrapped,(byte)0);
            }
            try {
                Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("ATOMIC_CONFIG_WRITE_REQUIRED",unsupported);
            }
            // Non-secret option: local Developer TLS exception is opt-in.
            boolean labTls="true".equalsIgnoreCase(
                System.getenv().getOrDefault("DBPULSE_LOCAL_TRUST_SELF_SIGNED","false"));
            Files.writeString(dir.resolve("settings.properties"),
                "localDeveloperSelfSignedTls="+labTls+"\n",StandardCharsets.UTF_8);
            System.out.println("DBPulse desktop metadata credential saved using Windows user DPAPI.");
        } catch (IOException failure) {
            throw new IllegalStateException("DESKTOP_CONFIG_SAVE_FAILED");
        } finally {
            Arrays.fill(sealed,(byte)0);
            if(tmp!=null)try{Files.deleteIfExists(tmp);}catch(IOException ignored){}
        }
    }

    private static String loadMetadataPassword() {
        Path file=directory().resolve(SECRET_FILE);
        byte[] wrapped;
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file) || Files.size(file)>8192) {
                throw new IOException("MISSING_OR_INVALID_CONFIG");
            }
            wrapped=Files.readAllBytes(file);
        } catch(IOException bad) {
            throw new IllegalStateException("DESKTOP_SETUP_REQUIRED");
        }
        try {
            if(wrapped.length<=HEADER.length)throw new IllegalStateException("DESKTOP_CONFIG_CORRUPT");
            for(int i=0;i<HEADER.length;i++){
                if(wrapped[i]!=HEADER[i])throw new IllegalStateException("DESKTOP_CONFIG_VERSION_INVALID");
            }
            byte[] ciphertext=Arrays.copyOfRange(wrapped,HEADER.length,wrapped.length);
            try{
                byte[] decrypted=Crypt32Util.cryptUnprotectData(ciphertext);
                try{return new String(decrypted,StandardCharsets.UTF_8);}
                finally{Arrays.fill(decrypted,(byte)0);}
            }catch(RuntimeException failure){
                throw new IllegalStateException("DESKTOP_CONFIG_CANNOT_DECRYPT");
            }finally{Arrays.fill(ciphertext,(byte)0);}
        }finally{Arrays.fill(wrapped,(byte)0);}
    }

    /** CLI-only native DPAPI smoke: never prints or returns decrypted metadata. */
    public static void checkProvisioned() {
        String recovered=loadMetadataPassword();
        if (recovered.isBlank()) throw new IllegalStateException("DESKTOP_CONFIG_EMPTY");
        System.out.println("DBPulse Windows user DPAPI metadata check passed.");
    }

    private static boolean developerSelfSignedAllowed() {
        Path config=directory().resolve("settings.properties");
        try {
            if (!Files.isRegularFile(config,LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(config) || Files.size(config)>4096) return false;
            var props=new java.util.Properties();
            try(var in=Files.newInputStream(config)){props.load(in);}
            return "true".equalsIgnoreCase(props.getProperty("localDeveloperSelfSignedTls","false"));
        }catch(IOException ignored){return false;}
    }

    public static void configure(SpringApplication springApp) {
        String metadataPassword=loadMetadataPassword();
        Map<String,Object> props=new HashMap<>();
        props.put("server.address","127.0.0.1");
        props.put("server.port",18081);
        props.put("spring.datasource.url","jdbc:postgresql://127.0.0.1:5544/dbpulse");
        props.put("spring.datasource.username","dbpulse");
        props.put("spring.datasource.password",metadataPassword);
        props.put("dbpulse.credentials.windows-dpapi-enabled",true);
        props.put("dbpulse.local-targets.enabled",true);
        props.put("dbpulse.local-targets.allowed-hosts","localhost,127.0.0.1,host.docker.internal");
        props.put("dbpulse.local-targets.allow-self-signed-lab",developerSelfSignedAllowed());
        props.put("dbpulse.mssql.enabled",false);
        springApp.addInitializers(context ->
            context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("dbpulse-windows-desktop",props)));
        springApp.setHeadless(false);
        springApp.addListeners((ApplicationListener<ApplicationReadyEvent>) event -> {
            try {
                if(Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(URI.create("http://127.0.0.1:18081/#targets"));
                } else {
                    System.out.println("Open http://127.0.0.1:18081/#targets");
                }
            } catch(IOException | UnsupportedOperationException failure) {
                System.out.println("Open http://127.0.0.1:18081/#targets");
            }
        });
    }
}
