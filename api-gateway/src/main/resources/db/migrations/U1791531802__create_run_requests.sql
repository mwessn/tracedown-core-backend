-- Back to runs without a handle. The results the handles named stay in
-- probe_results under the same ids; only the requests that have none yet are
-- forgotten, and a caller waiting on one gets 404 for it.
DROP TABLE run_requests;
