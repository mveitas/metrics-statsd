# metrics-statsd [![Build Status](https://travis-ci.org/ReadyTalk/metrics-statsd.png?branch=master)](https://travis-ci.org/ReadyTalk/metrics-statsd)

StatsD reporter for codahale/metrics. Supports versions 2 and 3.

## Quick Start

```java
MetricRegistry registry = new MetricRegistry();

// Using metrics2-statsd
StatsDReporter reporter = new StatsDReporter(registry,
    "statsd.example.com",
    8125,
    "metric.prefix");
reporter.start(reportIntervalInSec, TimeUnit.SECONDS);

// Using metrics3-statsd
StatsDReporter.forRegistry(registry)
    .build("statsd.example.com", 8125)
    .start(10, TimeUnit.SECONDS);
```

## Gradle

```groovy
repositories {
  mavenRepo(url: 'http://dl.bintray.com/readytalk/maven')
}

// for Metrics 2.x
compile('com.readytalk:metrics2-statsd:4.0.0-BETA1')
// for Metrics 3.x
compile('com.readytalk:metrics3-statsd:4.0.0-BETA1')
```

## Maven

Instructions for including metrics-statsd into a maven project can be found on the [bintray repository](https://bintray.com/readytalk/maven/metrics-statsd).

## Credits

This is based off of Sean Laurent's [metrics-statsd](https://github.com/organicveggie/metrics-statsd) and the graphite module of [Coda Hale's Metrics](https://github.com/codahale/metrics)
