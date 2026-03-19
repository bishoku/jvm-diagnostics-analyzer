package com.heapanalyzer;

import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Random;

public class GcLogBenchmark {

    public static void main(String[] args) {
        int listSize = 1_000_000;
        List<Double> pauseTimes = new ArrayList<>(listSize);
        Random random = new Random();
        for (int i = 0; i < listSize; i++) {
            pauseTimes.add(random.nextDouble() * 2000.0);
        }

        System.out.println("Warming up...");
        for (int i = 0; i < 10; i++) {
            runCurrent(pauseTimes);
            runOptimized(pauseTimes);
        }

        System.out.println("Running benchmark...");

        long start1 = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            runCurrent(pauseTimes);
        }
        long end1 = System.nanoTime();
        long currentAvg = (end1 - start1) / 50 / 1_000_000;

        long start2 = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            runOptimized(pauseTimes);
        }
        long end2 = System.nanoTime();
        long optimizedAvg = (end2 - start2) / 50 / 1_000_000;

        System.out.println("Baseline Average Time: " + currentAvg + " ms");
        System.out.println("Optimized Average Time: " + optimizedAvg + " ms");
        System.out.println("Improvement: " + String.format("%.2f%%", (double)(currentAvg - optimizedAvg) / currentAvg * 100));
    }

    private static void runCurrent(List<Double> pauseTimes) {
        double totalPause = pauseTimes.stream().mapToDouble(d -> d).sum();
        double avgPause = totalPause / pauseTimes.size();
        double maxPause = pauseTimes.stream().mapToDouble(d -> d).max().orElse(0);
        double minPause = pauseTimes.stream().mapToDouble(d -> d).min().orElse(0);
        long above200ms = pauseTimes.stream().filter(p -> p > 200).count();
        long above1000ms = pauseTimes.stream().filter(p -> p > 1000).count();
    }

    private static void runOptimized(List<Double> pauseTimes) {
        DoubleSummaryStatistics stats = pauseTimes.stream().mapToDouble(d -> d).summaryStatistics();
        double totalPause = stats.getSum();
        double avgPause = stats.getAverage();
        double maxPause = stats.getMax();
        double minPause = stats.getMin();

        long above200ms = 0;
        long above1000ms = 0;
        for (double p : pauseTimes) {
            if (p > 200) above200ms++;
            if (p > 1000) above1000ms++;
        }
    }
}
