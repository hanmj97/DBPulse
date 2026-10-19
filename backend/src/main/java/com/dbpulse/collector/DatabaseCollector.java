package com.dbpulse.collector;

import com.dbpulse.domain.MetricSnapshot;

// DBMS adapters implement this interface. PostgreSQL can be added without rewriting the API.
public interface DatabaseCollector {
    MetricSnapshot collect() throws Exception;
}
