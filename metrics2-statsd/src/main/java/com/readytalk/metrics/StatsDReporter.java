/**
 * Copyright (C) 2012-2013 Sean Laurent
 * Copyright (C) 2013 metrics-statsd contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.readytalk.metrics;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Clock;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.Metered;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricPredicate;
import com.yammer.metrics.core.MetricProcessor;
import com.yammer.metrics.core.MetricsRegistry;
import com.yammer.metrics.core.Sampling;
import com.yammer.metrics.core.Summarizable;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.VirtualMachineMetrics;
import com.yammer.metrics.reporting.AbstractPollingReporter;
import com.yammer.metrics.stats.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;

public class StatsDReporter extends AbstractPollingReporter implements MetricProcessor<Long> {
  private static final Logger LOG = LoggerFactory.getLogger(StatsDReporter.class);

  protected final String prefix;
  protected final MetricPredicate predicate;
  protected final Clock clock;
  protected final VirtualMachineMetrics vm;

  private final StatsD statsD;

  public StatsDReporter(String host, int port) {
    this(Metrics.defaultRegistry(), host, port, null);
  }

  public StatsDReporter(String host, int port, String prefix) {
    this(Metrics.defaultRegistry(), host, port, prefix);
  }

  public StatsDReporter(MetricsRegistry metricsRegistry, String host, int port) {
    this(metricsRegistry, host, port, null);
  }

  public StatsDReporter(MetricsRegistry metricsRegistry, String host, int port, String prefix) {
    this(metricsRegistry,
        prefix,
        MetricPredicate.ALL,
        Clock.defaultClock(),
        new StatsD(host, port));
  }

  public StatsDReporter(MetricsRegistry metricsRegistry, String prefix, MetricPredicate predicate, Clock clock, StatsD statsD) {
    this(metricsRegistry, prefix, predicate, clock, VirtualMachineMetrics.getInstance(), statsD);
  }

  public StatsDReporter(MetricsRegistry metricsRegistry, String prefix, MetricPredicate predicate, Clock clock, VirtualMachineMetrics vm, StatsD statsD) {
    this(metricsRegistry, prefix, predicate, clock, vm, "statsd-reporter", statsD);
  }

  public StatsDReporter(MetricsRegistry metricsRegistry, String prefix, MetricPredicate predicate, Clock clock, VirtualMachineMetrics vm, String name, StatsD statsD) {
    super(metricsRegistry, name);

    this.vm = vm;

    this.clock = clock;

    if (prefix != null) {
      // Pre-append the "." so that we don't need to make anything conditional later.
      this.prefix = prefix + ".";
    } else {
      this.prefix = "";
    }
    this.predicate = predicate;
    this.statsD = statsD;
  }

  @Override
  public void run() {
    try {
      statsD.connect();
      final long epoch = clock.time() / 1000;
      printRegularMetrics(epoch);
    } catch (IOException e) {
      LOG.info("Failed to connect or print metrics to statsd", e);
    } finally {
      try {
        statsD.close();
      } catch (IOException e) {
        LOG.info("Failure when closing statsd connection", e);
      }
    }

  }

  protected void printRegularMetrics(long epoch) {
    for (Map.Entry<String, SortedMap<MetricName, Metric>> entry : getMetricsRegistry().groupedMetrics(predicate).entrySet()) {
      for (Map.Entry<MetricName, Metric> subEntry : entry.getValue().entrySet()) {
        final Metric metric = subEntry.getValue();
        if (metric != null) {
          try {
            metric.processWith(this, subEntry.getKey(), epoch);
          } catch (Exception ignored) {
            LOG.error("Error printing regular metrics:", ignored);
          }
        }
      }
    }
  }

  @Override
  public void processMeter(MetricName name, Metered meter, Long epoch) {
    final String sanitizedName = sanitizeName(name);
    sendToStatsD(sanitizedName, formatNumber(meter.count()));
    sendToStatsD(sanitizedName + ".meanRate", formatNumber(meter.meanRate()));
    sendToStatsD(sanitizedName + ".1MinuteRate", formatNumber(meter.oneMinuteRate()));
    sendToStatsD(sanitizedName + ".5MinuteRate", formatNumber(meter.fiveMinuteRate()));
    sendToStatsD(sanitizedName + ".15MinuteRate", formatNumber(meter.fifteenMinuteRate()));
  }

  @Override
  public void processCounter(MetricName name, Counter counter, Long epoch) {
    sendToStatsD(sanitizeName(name), formatNumber(counter.count()));
  }

  @Override
  public void processHistogram(MetricName name, Histogram histogram, Long epoch) {
    final String sanitizedName = sanitizeName(name);
    sendSummarizable(sanitizedName, histogram);
    sendSampling(sanitizedName, histogram);
  }

  @Override
  public void processTimer(MetricName name, Timer timer, Long epoch) {
    processMeter(name, timer, epoch);
    final String sanitizedName = sanitizeName(name);
    sendSummarizable(sanitizedName, timer);
    sendSampling(sanitizedName, timer);
  }

  @Override
  public void processGauge(MetricName name, Gauge<?> gauge, Long epoch) {
    String stringValue = format(gauge.value());
    if (stringValue != null) {
      sendToStatsD(sanitizeName(name), stringValue);
    }
  }

  protected void sendSummarizable(String sanitizedName, Summarizable metric) {
    sendToStatsD(sanitizedName + ".min", formatNumber(metric.min()));
    sendToStatsD(sanitizedName + ".max", formatNumber(metric.max()));
    sendToStatsD(sanitizedName + ".mean", formatNumber(metric.mean()));
    sendToStatsD(sanitizedName + ".stddev", formatNumber(metric.stdDev()));
  }

  protected void sendSampling(String sanitizedName, Sampling metric) {
    final Snapshot snapshot = metric.getSnapshot();
    sendToStatsD(sanitizedName + ".median", formatNumber(snapshot.getMedian()));
    sendToStatsD(sanitizedName + ".75percentile", formatNumber(snapshot.get75thPercentile()));
    sendToStatsD(sanitizedName + ".95percentile", formatNumber(snapshot.get95thPercentile()));
    sendToStatsD(sanitizedName + ".98percentile", formatNumber(snapshot.get98thPercentile()));
    sendToStatsD(sanitizedName + ".99percentile", formatNumber(snapshot.get99thPercentile()));
    sendToStatsD(sanitizedName + ".999percentile", formatNumber(snapshot.get999thPercentile()));
  }

  protected String sanitizeName(MetricName name) {
    final StringBuilder sb = new StringBuilder()
        .append(name.getGroup())
        .append('.')
        .append(name.getType())
        .append('.');
    if (name.hasScope()) {
      sb.append(name.getScope())
          .append('.');
    }
    return sb.append(name.getName()).toString();
  }

  private void sendToStatsD(String metricName, String metricValue) {
    statsD.send(prefix + metricName, metricValue);
  }

  private String format(final Object o) {
    if (o instanceof Float) {
      return formatNumber(((Float) o).doubleValue());
    } else if (o instanceof Double) {
      return formatNumber((Double) o);
    } else if (o instanceof Byte) {
      return formatNumber(((Byte) o).longValue());
    } else if (o instanceof Short) {
      return formatNumber(((Short) o).longValue());
    } else if (o instanceof Integer) {
      return formatNumber(((Integer) o).longValue());
    } else if (o instanceof Long) {
      return formatNumber((Long) o);
    } else if (o instanceof BigInteger) {
      return formatNumber((BigInteger) o);
    } else if (o instanceof BigDecimal) {
      return formatNumber(((BigDecimal) o).doubleValue());
    }
    return null;
  }

  private String formatNumber(final BigInteger n) {
    return String.valueOf(n);
  }

  private String formatNumber(final long n) {
    return Long.toString(n);
  }

  private String formatNumber(final double v) {
    return String.format(Locale.US, "%2.2f", v);
  }
}
