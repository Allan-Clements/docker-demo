package com.scratch;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.google.common.collect.ImmutableMap;
import org.junit.rules.ExternalResource;

import static org.awaitility.Awaitility.await;

public class DockerWaitRule
    extends ExternalResource
{
  private final Map<String, String> messagesToWaitForByContainerName;

  private final long timeLimit;

  public DockerWaitRule(LocalTime timeLimit, Map<String, String> messagesToWaitForByContainerName) {
    this.messagesToWaitForByContainerName = ImmutableMap.copyOf(messagesToWaitForByContainerName);
    this.timeLimit = System.currentTimeMillis() + ChronoUnit.MILLIS.between(LocalTime.now(), timeLimit);
  }

  private long getMillisRemaining() {
    return timeLimit - System.currentTimeMillis();
  }

  @Override
  protected void before() throws Throwable {
    waitForContainerMessages();
  }

  private void waitForContainerMessages() {
    for (Map.Entry<String, String> anEntry : messagesToWaitForByContainerName.entrySet()) {
      await().atMost(getMillisRemaining(), TimeUnit.MILLISECONDS).pollInterval(10, TimeUnit.SECONDS)
          .until(checkForReadyMessageForContainer(anEntry.getKey(), anEntry.getValue()));
    }
  }

  private static Callable<Boolean> checkForReadyMessageForContainer(String containerName, String readyMessage) {
    return () -> {
      AtomicBoolean wasContainerReadyMessageObserved = new AtomicBoolean(false);
      try (DockerClient dockerClient = DockerClientBuilder.getInstance().build()) {

        System.out.println(String.format("Looking for \"%s\" from container %s", readyMessage, containerName));

        try {
          dockerClient.logContainerCmd(containerName)
              .withSince(0) //read everything the container has written
              .withStdOut(true)
              .withStdErr(true)
              .exec(new LogContainerResultCallback()
              {
                @Override
                public void onNext(final Frame item) {
                  String logLine = new String(item.getPayload());
                  if (logLine.contains(readyMessage)) {
                    System.out.println(String.format("Observed \"%s\" for container %s", readyMessage, containerName));
                    wasContainerReadyMessageObserved.set(true);
                  }
                }
              }).awaitCompletion();
        }
        catch (NotFoundException e) {
          //doNothing(), container hasn't appeared yet
        }
        return wasContainerReadyMessageObserved.get();
      }
    };
  }
}