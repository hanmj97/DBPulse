import React, { useState } from 'react';
import {
  Activity, Bell, ChartNoAxesCombined, CheckCircle2, ChevronDown, Clock3,
  Database, Gauge, GitBranch, LayoutDashboard, LockKeyhole, Search,
  Server, Settings2, ShieldAlert, SlidersHorizontal, Sparkles, X
} from 'lucide-react';

const links = [
  {key:'overview', label:'개요', icon:LayoutDashboard, href:'#overview'},
  {key:'incidents', label:'인시던트', icon:ShieldAlert, href:'#incidents'},
  {key:'waits', label:'대기 분석', icon:Clock3, href:'#wait-diagnostics'},
  {key:'deadlocks', label:'Deadlock', icon:GitBranch, href:'#deadlocks'},
  {key:'blocking', label:'Blocking', icon:LockKeyhole, href:'#blocking-chains'},
  {key:'performance', label:'성능', icon:Gauge, href:'#performance'},
  {key:'queries', label:'쿼리', icon:ChartNoAxesCombined, href:'#query-performance'},
  {key:'investigation', label:'원인 분석', icon:Sparkles, href:'#investigation'}
];

export const PAGE_NAMES = Object.fromEntries(links.map(link => [link.key, link.label]));
PAGE_NAMES.targets = '대상 서버';

export function DashboardShell({
  children, page, target, incidentCount, incidentError, updated,
  sampleLimit, onSampleLimit, search, onSearch, onNavigate
}) {
  const [mobileOpen, setMobileOpen] = useState(false);
  const online = target?.collectorStatus === 'ONLINE';
  const label = target?.collectorStatus === 'ONLINE' ? '수집 정상'
    : target?.collectorStatus === 'OFFLINE' ? '수집 오류'
    : target?.collectorStatus === 'NOT_CONFIGURED' ? '설정 필요'
    : '수집 상태 확인 중';

  const navigation = (item) =>
    <a className={'rev-nav ' + (page === item.key ? 'active' : '')}
       href={item.href} key={item.key} aria-current={page === item.key ? 'page' : undefined}
       onClick={() => { setMobileOpen(false); onNavigate?.(item.key); }}>
      <item.icon size={17} strokeWidth={1.85} />
      <span>{item.label}</span>
      {item.key === 'incidents' && incidentCount > 0 &&
        <span className="rev-count">{incidentCount}</span>}
    </a>;

  return <div className="rev-layout">
    <aside className={'rev-sidebar ' + (mobileOpen ? 'mobile-open' : '')}>
      <a className="rev-brand" href="#overview" onClick={() => setMobileOpen(false)}>
        <span className="rev-logo"><Activity size={27} strokeWidth={2.8}/></span>
        <span>DBPulse<small>DATABASE OPERATIONS</small></span>
      </a>
      <div className="rev-sidebar-section">WORKSPACE</div>
      <nav aria-label="주 메뉴">{links.map(navigation)}</nav>
      <div className="rev-sidebar-divider"/>
      <div className="rev-sidebar-section">MANAGEMENT</div>
      {navigation({key:'targets',label:'대상 서버', icon:Server,href:'#targets'})}
      <div className="rev-nav rev-nav-disabled" title="리포트 제공 예정"><SlidersHorizontal size={17}/>리포트<small>예정</small></div>
      <div className="rev-nav rev-nav-disabled" title="설정 화면 제공 예정"><Settings2 size={17}/>설정<small>예정</small></div>
      <div className="rev-sidebar-spacer"/>
      <div className="rev-target-footer">
        <div className="rev-target-footer-head"><Database size={17}/><span>현재 모니터링 대상</span></div>
        <strong>{target?.name || '연결 대상 확인 중'}</strong>
        <span className={'rev-footer-status ' + (online ? 'on' : '')}>
          <span className="rev-status-dot"/>{label}
        </span>
        <small>v0.8.0 · 로컬 단일 대상</small>
      </div>
    </aside>
    {mobileOpen && <button className="rev-mobile-backdrop" onClick={() => setMobileOpen(false)} aria-label="메뉴 닫기"/>}

    <div className="rev-body">
      <header className="rev-topbar">
        <button className="rev-mobile-menu" type="button"
          aria-label="메뉴 열기" onClick={() => setMobileOpen(!mobileOpen)}>☰</button>
        <form className="rev-search" onSubmit={e => {
          e.preventDefault();
          window.location.hash = '#incidents';
          onNavigate?.('incidents');
        }}>
          <Search size={17}/>
          <input aria-label="인시던트 검색" value={search}
            onChange={e => onSearch(e.target.value)}
            placeholder="인시던트 번호 또는 유형 검색..."
          />
          <button type="submit" aria-label="검색 실행">검색</button>
        </form>
        <div className="rev-topbar-actions">
          <div className="rev-time-select"><Clock3 size={15}/>
            <select aria-label="성능 차트 샘플 범위" value={sampleLimit}
              onChange={e => onSampleLimit(Number(e.target.value))}>
              <option value={15}>최근 15개 샘플</option>
              <option value={30}>최근 30개 샘플</option>
              <option value={60}>최근 60개 샘플</option>
            </select><ChevronDown size={13}/>
          </div>
          <span className={'rev-system-pill ' + (online ? 'online' : 'unknown')}>
            {online ? <CheckCircle2 size={15}/> : <ShieldAlert size={15}/>}
            {label}
          </span>
          <a href="#incidents" className="rev-notice" aria-label="인시던트 이력 열기"
             onClick={() => onNavigate?.('incidents')}>
            <Bell size={19}/>{incidentCount > 0 && <span>{incidentCount}</span>}
          </a>
          <div className="rev-avatar" title="로컬 개발 환경">DB</div>
        </div>
      </header>
      <main className="rev-main">
        <div className="rev-page-heading">
          <div>
            <div className="rev-page-eyebrow">DBPULSE / {PAGE_NAMES[page] || '개요'}</div>
            <h1>{page === 'overview' ? '데이터베이스 운영 현황' : PAGE_NAMES[page] || '개요'}</h1>
            <p>{page === 'overview'
              ? '실제 수집 데이터로 연결 상태와 성능 이상 신호를 한눈에 확인합니다.'
              : '기존 모니터링 기능을 유지하면서 분석 내용을 집중해서 확인합니다.'}</p>
          </div>
          <span className="rev-updated">마지막 화면 갱신 {updated?.toLocaleTimeString('ko-KR', {hour12:false}) || '—'}</span>
        </div>
        {incidentError && page === 'overview' && <div className="rev-inline-warning">
          인시던트 API 연결 오류: 일부 요약 지표를 표시할 수 없습니다.</div>}
        {children}
      </main>
    </div>
  </div>;
}
