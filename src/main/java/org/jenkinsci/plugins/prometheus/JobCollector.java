package org.jenkinsci.plugins.prometheus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.prometheus.client.Gauge;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.prometheus.util.Callback;
import org.jenkinsci.plugins.prometheus.util.Jobs;
import org.jenkinsci.plugins.prometheus.util.Runs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hudson.model.Job;
import hudson.model.Run;
import io.prometheus.client.Collector;
import org.jenkinsci.plugins.prometheus.config.PrometheusConfiguration;

public class JobCollector extends Collector {
    private static final Logger logger = LoggerFactory.getLogger(JobCollector.class);

    private long previousRun = 0;
    private String namespace;
    private Gauge buildDuration;

    public JobCollector() {
    	// get the namespace from the environment first
        namespace = System.getenv("PROMETHEUS_NAMESPACE");
        if (StringUtils.isEmpty(namespace)) {
        	// when the environment variable isn't set, try the system configuration
        	namespace = PrometheusConfiguration.get().getDefaultNamespace();
            logger.debug("Since the environment variable 'PROMETHEUS_NAMESPACE' is empty, using the value [{}] from the master configuration (empty strings are allowed)"+namespace);
        }
        logger.info("The prometheus namespace is [{}]", namespace);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        logger.debug("Collecting metrics for prometheus");
        final List<MetricFamilySamples> samples = new ArrayList<>();
        final List<Job> jobs = new ArrayList<>();
        final String fullname = "builds";
        final String subsystem = "jenkins";

        logger.debug("getting duration of each build");
        buildDuration = Gauge.build().
                name(fullname + "_duration").
                subsystem(subsystem).namespace(namespace).
                labelNames(new String[]{"job", "status", "build"}).
                help("Build duration by Job").
                create();

        final String[] jobNames = PrometheusConfiguration.get().getMonitoringJobNames().split(",");
        if (jobNames.length == 0) return samples;
        Jobs.forEachJob(new Callback<Job>() {
            @Override
            public void invoke(Job job) {
                logger.debug("Determining if we are already appending metrics for job [{}]", job.getName());
                for (Job old : jobs) {
                    if (old.getFullName().equals(job.getFullName())) {
                        // already added
                        logger.debug("Job [{}] is already added", job.getName());
                        return;
                    }
                }
                jobs.add(job);
                logger.debug("Job [{}] is checking on starts with [{}]", job.getName(), Arrays.toString(jobNames));
                if (StringUtils.startsWithAny(job.getFullName(), jobNames)) {
                    appendJobMetrics(job);
                }
            }
        });
        final List<MetricFamilySamples> buildDurationList = buildDuration.collect();
        if (buildDurationList.get(0).samples.size() > 0){
            logger.debug("Adding [{}] samples from build duration", buildDurationList.get(0).samples.size());
            samples.addAll(buildDurationList);
        }
        previousRun = System.currentTimeMillis();
        return samples;
    }

    protected void appendJobMetrics(Job job) {
        Run run = null;
        try {
            run = job.getLastBuild();
        } catch (Exception e) {
            logger.error("Failed to get last build to analyze");
        }
        long runStartTime = run.getStartTimeInMillis();
        while (run != null) {
            // stop collecting metrics from previous builds - they already should be in db
            if (previousRun > runStartTime) break;

            if (Runs.includeBuildInMetrics(run)) {
                logger.debug("getting metrics for run [{}] from job [{}]", run.getNumber(), job.getName());
                long duration = run.getDuration();
                buildDuration.labels(new String[]{
                            job.getFullName(),
                            run.getResult().toString(),
                            String.valueOf(run.getNumber())
                }).set(duration);
            }
            try {
                run = run.getPreviousBuild();
                runStartTime = run.getStartTimeInMillis();
            } catch (Exception e) {
                logger.error("Failed to get previous build to analyze");
            }
        }
    }
}
