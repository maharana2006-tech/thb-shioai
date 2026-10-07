package com.multiship.backend.service.dtc.scheduler;

import java.util.Map;

/**
 * The work behind one {@code dtc_scheduler_job} row. {@link DtcSchedulerEngine}
 * decides when to call it; the runner only does the work. A runner whose key
 * has no job row is never called.
 */
public interface DtcSchedulerJobRunner {

    /** Matches {@code dtc_scheduler_job.job_key}. */
    String jobKey();

    /** @param params the job's {@code params_json}, parsed (empty when unset). */
    Outcome run(Map<String, Object> params) throws Exception;

    record Outcome(String status, String message) {
        public static Outcome success(String message) { return new Outcome(DtcSchedulerEngine.SUCCESS, message); }
        public static Outcome skipped(String message) { return new Outcome(DtcSchedulerEngine.SKIPPED, message); }
    }
}
