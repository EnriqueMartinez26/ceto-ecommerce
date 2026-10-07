package ar.cetobhb.support;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.BurstRunner.Report;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BurstRunnerTest {

    @Test
    void reportsEveryTaskInFlightWhenTheTasksOverlap() {
        int n = 4;
        CyclicBarrier allInside = new CyclicBarrier(n);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int id = i;
            tasks.add(() -> {
                allInside.await(10, TimeUnit.SECONDS);
                return id;
            });
        }

        Report<Integer> report = BurstRunner.runMeasured(tasks);

        assertThat(report.maxInFlight()).isEqualTo(n);
        assertThat(report.outcomes()).extracting(BurstRunner.Outcome::value).containsExactly(0, 1, 2, 3);
    }

    @Test
    void reportsOneWhenTheTasksNeverOverlap() {
        List<Callable<Integer>> tasks = List.of(() -> 1);

        assertThat(BurstRunner.runMeasured(tasks).maxInFlight()).isEqualTo(1);
    }

    @Test
    void reportsOverlapEvenWhenEveryTaskFails() {
        int n = 3;
        CyclicBarrier allInside = new CyclicBarrier(n);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(() -> {
                allInside.await(10, TimeUnit.SECONDS);
                throw new IllegalStateException("boom");
            });
        }

        Report<Integer> report = BurstRunner.runMeasured(tasks);

        assertThat(report.maxInFlight()).isEqualTo(n);
        assertThat(report.outcomes()).noneMatch(BurstRunner.Outcome::succeeded);
    }
}
