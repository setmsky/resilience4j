/*
 *
 *  Copyright 2016 Robert Winkler and Bohdan Storozhuk
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package io.github.resilience4j.ratelimiter.internal;

import com.jayway.awaitility.core.ConditionFactory;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.awaitility.Duration.FIVE_HUNDRED_MILLISECONDS;
import static io.vavr.control.Try.run;
import static java.lang.Thread.State.*;
import static java.time.Duration.ZERO;
import static org.assertj.core.api.BDDAssertions.then;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


public class SemaphoreBasedRateLimiterImplTest {

    private static final int LIMIT = 2;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REFRESH_PERIOD = Duration.ofMillis(100);
    private static final String CONFIG_MUST_NOT_BE_NULL = "RateLimiterConfig must not be null";
    private static final String NAME_MUST_NOT_BE_NULL = "Name must not be null";
    private static final Object O = new Object();
    @Rule
    public ExpectedException exception = ExpectedException.none();
    private RateLimiterConfig config;

    private static ConditionFactory awaitImpatiently() {
        return await()
            .pollDelay(1, TimeUnit.MICROSECONDS)
            .pollInterval(2, TimeUnit.MILLISECONDS);
    }

    @Before
    public void init() {
        config = RateLimiterConfig.custom()
            .timeoutDuration(TIMEOUT)
            .limitRefreshPeriod(REFRESH_PERIOD)
            .limitForPeriod(LIMIT)
            .build();
    }

    @Test
    public void rateLimiterCreationWithProvidedScheduler() throws Exception {
        ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);
        RateLimiterConfig configSpy = spy(config);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", configSpy, scheduledExecutorService);

        ArgumentCaptor<Runnable> refreshLimitRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduledExecutorService)
            .scheduleAtFixedRate(
                refreshLimitRunnableCaptor.capture(),
                eq(config.getLimitRefreshPeriod().toNanos()),
                eq(config.getLimitRefreshPeriod().toNanos()),
                eq(TimeUnit.NANOSECONDS)
            );

        Runnable refreshLimitRunnable = refreshLimitRunnableCaptor.getValue();

        then(limit.getPermission(ZERO)).isTrue();
        then(limit.reservePermission(ZERO)).isNegative();
        then(limit.reservePermission(TIMEOUT)).isNegative();

        then(limit.getPermission(ZERO)).isTrue();
        then(limit.reservePermission(ZERO)).isNegative();
        then(limit.reservePermission(TIMEOUT)).isNegative();

        then(limit.getPermission(ZERO)).isFalse();
        then(limit.reservePermission(ZERO)).isNegative();
        then(limit.reservePermission(TIMEOUT)).isNegative();

        Thread.sleep(REFRESH_PERIOD.toMillis() * 2);
        verify(configSpy, times(1)).getLimitForPeriod();

        refreshLimitRunnable.run();

        verify(configSpy, times(2)).getLimitForPeriod();

        then(limit.getPermission(ZERO)).isTrue();
        then(limit.getPermission(ZERO)).isTrue();
        then(limit.getPermission(ZERO)).isFalse();
    }

    @Test
    public void rateLimiterCreationWithDefaultScheduler() throws Exception {
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", config);
        awaitImpatiently().atMost(FIVE_HUNDRED_MILLISECONDS)
            .until(() -> limit.getPermission(ZERO), equalTo(false));
        awaitImpatiently().atMost(110, TimeUnit.MILLISECONDS)
            .until(() -> limit.getPermission(ZERO), equalTo(true));
    }

    @Test
    public void getPermissionAndMetrics() throws Exception {

        ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);
        RateLimiterConfig configSpy = spy(config);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", configSpy, scheduledExecutorService);
        RateLimiter.Metrics detailedMetrics = limit.getMetrics();

        SynchronousQueue<Object> synchronousQueue = new SynchronousQueue<>();
        Thread thread = new Thread(() -> {
            run(() -> {
                for (int i = 0; i < LIMIT; i++) {
                    synchronousQueue.put(O);
                    limit.getPermission(TIMEOUT);
                }
                limit.getPermission(TIMEOUT);
            });
        });
        thread.setDaemon(true);
        thread.start();

        for (int i = 0; i < LIMIT; i++) {
            synchronousQueue.take();
        }

        then(limit.reservePermission(ZERO)).isNegative();
        then(limit.reservePermission(TIMEOUT)).isNegative();

        awaitImpatiently()
            .atMost(100, TimeUnit.MILLISECONDS).until(detailedMetrics::getAvailablePermissions, equalTo(0));
        awaitImpatiently()
            .atMost(2, TimeUnit.SECONDS).until(thread::getState, equalTo(TIMED_WAITING));
        then(detailedMetrics.getAvailablePermissions()).isEqualTo(0);

        then(limit.reservePermission(ZERO)).isNegative();
        then(limit.reservePermission(TIMEOUT)).isNegative();

        limit.refreshLimit();
        awaitImpatiently()
            .atMost(100, TimeUnit.MILLISECONDS).until(detailedMetrics::getAvailablePermissions, equalTo(1));
        awaitImpatiently()
            .atMost(2, TimeUnit.SECONDS).until(thread::getState, equalTo(TERMINATED));
        then(detailedMetrics.getAvailablePermissions()).isEqualTo(1);

        limit.changeLimitForPeriod(3);
        limit.refreshLimit();
        then(detailedMetrics.getAvailablePermissions()).isEqualTo(3);
        then(limit.reservePermission(ZERO)).isNegative();
        then(limit.reservePermission(TIMEOUT)).isNegative();
    }

    @Test
    public void changeDefaultTimeoutDuration() throws Exception {
        ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);
        RateLimiter rateLimiter = new SemaphoreBasedRateLimiter("some", config, scheduledExecutorService);
        RateLimiterConfig rateLimiterConfig = rateLimiter.getRateLimiterConfig();
        then(rateLimiterConfig.getTimeoutDuration()).isEqualTo(TIMEOUT);
        then(rateLimiterConfig.getTimeoutDurationInNanos()).isEqualTo(TIMEOUT.toNanos());
        then(rateLimiterConfig.getLimitForPeriod()).isEqualTo(LIMIT);
        then(rateLimiterConfig.getLimitRefreshPeriod()).isEqualTo(REFRESH_PERIOD);
        then(rateLimiterConfig.getLimitRefreshPeriodInNanos()).isEqualTo(REFRESH_PERIOD.toNanos());

        rateLimiter.changeTimeoutDuration(Duration.ofSeconds(1));
        then(rateLimiterConfig != rateLimiter.getRateLimiterConfig()).isTrue();
        rateLimiterConfig = rateLimiter.getRateLimiterConfig();
        then(rateLimiterConfig.getTimeoutDuration()).isEqualTo(Duration.ofSeconds(1));
        then(rateLimiterConfig.getTimeoutDurationInNanos()).isEqualTo(Duration.ofSeconds(1).toNanos());
        then(rateLimiterConfig.getLimitForPeriod()).isEqualTo(LIMIT);
        then(rateLimiterConfig.getLimitRefreshPeriod()).isEqualTo(REFRESH_PERIOD);
        then(rateLimiterConfig.getLimitRefreshPeriodInNanos()).isEqualTo(REFRESH_PERIOD.toNanos());
    }

    @Test
    public void changeLimitForPeriod() throws Exception {
        ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);
        RateLimiter rateLimiter = new SemaphoreBasedRateLimiter("some", config, scheduledExecutorService);
        RateLimiterConfig rateLimiterConfig = rateLimiter.getRateLimiterConfig();
        then(rateLimiterConfig.getTimeoutDuration()).isEqualTo(TIMEOUT);
        then(rateLimiterConfig.getTimeoutDurationInNanos()).isEqualTo(TIMEOUT.toNanos());
        then(rateLimiterConfig.getLimitForPeriod()).isEqualTo(LIMIT);
        then(rateLimiterConfig.getLimitRefreshPeriod()).isEqualTo(REFRESH_PERIOD);
        then(rateLimiterConfig.getLimitRefreshPeriodInNanos()).isEqualTo(REFRESH_PERIOD.toNanos());

        rateLimiter.changeLimitForPeriod(LIMIT * 2);
        then(rateLimiterConfig != rateLimiter.getRateLimiterConfig()).isTrue();
        rateLimiterConfig = rateLimiter.getRateLimiterConfig();
        then(rateLimiterConfig.getTimeoutDuration()).isEqualTo(TIMEOUT);
        then(rateLimiterConfig.getTimeoutDurationInNanos()).isEqualTo(TIMEOUT.toNanos());
        then(rateLimiterConfig.getLimitForPeriod()).isEqualTo(LIMIT * 2);
        then(rateLimiterConfig.getLimitRefreshPeriod()).isEqualTo(REFRESH_PERIOD);
        then(rateLimiterConfig.getLimitRefreshPeriodInNanos()).isEqualTo(REFRESH_PERIOD.toNanos());
    }

    @Test
    public void getPermissionInterruption() throws Exception {
        ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);
        RateLimiterConfig configSpy = spy(config);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", configSpy, scheduledExecutorService);
        limit.getPermission(ZERO);
        limit.getPermission(ZERO);

        Thread thread = new Thread(() -> {
            limit.getPermission(TIMEOUT);
            while (true) {
                Function.identity().apply(1);
            }
        });
        thread.setDaemon(true);
        thread.start();

        awaitImpatiently()
            .atMost(2, TimeUnit.SECONDS).until(thread::getState, equalTo(TIMED_WAITING));

        thread.interrupt();

        awaitImpatiently()
            .atMost(2, TimeUnit.SECONDS).until(thread::getState, equalTo(RUNNABLE));
        awaitImpatiently()
            .atMost(100, TimeUnit.MILLISECONDS).until(thread::isInterrupted);
    }

    @Test
    public void getName() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", config, scheduler);
        then(limit.getName()).isEqualTo("test");
    }

    @Test
    public void getMetrics() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", config, scheduler);
        RateLimiter.Metrics metrics = limit.getMetrics();
        then(metrics.getNumberOfWaitingThreads()).isEqualTo(0);
    }

    @Test
    public void getRateLimiterConfig() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", config, scheduler);
        then(limit.getRateLimiterConfig()).isEqualTo(config);
    }

    @Test
    public void isUpperLimitedForPermissions() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", config, scheduler);
        RateLimiter.Metrics metrics = limit.getMetrics();
        then(metrics.getAvailablePermissions()).isEqualTo(2);
        limit.refreshLimit();
        then(metrics.getAvailablePermissions()).isEqualTo(2);
    }

    @Test
    public void getDetailedMetrics() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", config, scheduler);
        RateLimiter.Metrics metrics = limit.getMetrics();
        then(metrics.getNumberOfWaitingThreads()).isEqualTo(0);
        then(metrics.getAvailablePermissions()).isEqualTo(2);
    }

    @Test
    public void constructionWithNullName() throws Exception {
        exception.expect(NullPointerException.class);
        exception.expectMessage(NAME_MUST_NOT_BE_NULL);
        new SemaphoreBasedRateLimiter(null, config, null);
    }

    @Test
    public void constructionWithNullConfig() throws Exception {
        exception.expect(NullPointerException.class);
        exception.expectMessage(CONFIG_MUST_NOT_BE_NULL);
        new SemaphoreBasedRateLimiter("test", null, null);
    }
}
