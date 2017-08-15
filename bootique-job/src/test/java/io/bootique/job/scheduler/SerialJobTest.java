package io.bootique.job.scheduler;

import io.bootique.BQRuntime;
import io.bootique.Bootique;
import io.bootique.job.fixture.TestJobModule;
import io.bootique.job.runnable.JobOutcome;
import io.bootique.job.runnable.JobResult;
import io.bootique.job.runtime.JobModule;
import io.bootique.logback.LogbackModule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class SerialJobTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(SerialJobTest.class);

    private BQRuntime runtime;
    private ExecutorService executor;

    @Before
    public void setUp() {
        runtime = Bootique.app().module(JobModule.class).module(TestJobModule.class).module(LogbackModule.class).createRuntime();
        executor = Executors.newCachedThreadPool(
                r -> new Thread(r, SerialJobTest.class.getSimpleName() + "-executor [" + r.hashCode() + "]"));
    }

    @After
    public void tearDown() {
        runtime.shutdown();
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while waiting for executor shutdown", e);
        }
        if (!executor.isTerminated()) {
            throw new IllegalStateException("Failed to shutdown executor");
        }
    }

    @Test
    public void testSerialJob() {
        String jobName = "serialjob1";
        Scheduler scheduler = runtime.getInstance(Scheduler.class);
        int count = 10;

        Queue<JobResult> resultQueue = new LinkedBlockingQueue<>(count + 1);
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            executor.submit(() -> {
                try {
                    resultQueue.add(scheduler.runOnce(jobName).get());
                } catch (Exception e) {
                    LOGGER.error("Failed to run job", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean allRun;
        try {
            allRun = latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while waiting for job execution", e);
        }
        // check if any of the job instances hanged up
        if (!allRun) {
            throw new RuntimeException("Timeout while waiting for job execution");
        }

        // verify that all jobs have finished execution without throwing an exception
        assertEquals(count, resultQueue.size());

        // one of the jobs is expected to finish successfully
        // (which one is unknown though, because scheduler also uses an executor internally)
        boolean foundOneSuccessful = false;
        Iterator<JobResult> iter = resultQueue.iterator();
        while (iter.hasNext()) {
            JobResult result = iter.next();
            if (result.getOutcome() == JobOutcome.SUCCESS) {
                iter.remove();
                foundOneSuccessful = true;
                break;
            }
        }
        if (!foundOneSuccessful) {
            throw new RuntimeException("No jobs finished successfully, expected exactly one");
        }

        for (int i = 1; i < count; i++) {
            // we expect all other simultaneous jobs to be skipped by scheduler;
            // otherwise we expect failure, because io.bootique.job.fixture.ExecutableJob
            // throws an exception if run more than once
            JobOutcome actualOutcome = resultQueue.poll().getOutcome();
            assertEquals("Execution #" + (i+1) + " was not skipped; actual outcome: " + actualOutcome,
                    JobOutcome.SKIPPED, actualOutcome);
        }
    }
}