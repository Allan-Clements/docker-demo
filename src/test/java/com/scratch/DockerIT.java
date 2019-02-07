package com.scratch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.LocalTime;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.google.common.collect.ImmutableMap;
import com.palantir.docker.compose.DockerComposeRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;

public class DockerIT
{
  private final static DockerComposeRule DOCKER_COMPOSE_RULE = DockerComposeRule.builder()
      .file("src/test/resources/docker-compose.yml")
      .saveLogsTo("target/docker-logs")
      .build();

  private final static DockerWaitRule DOCKER_WAIT_RULE = new DockerWaitRule(LocalTime.now().plusMinutes(5),
      ImmutableMap.of("localstack", "Ready"));

  @ClassRule
  public static final RuleChain CLASS_RULE_CHAIN = RuleChain.outerRule(DOCKER_COMPOSE_RULE).around(DOCKER_WAIT_RULE);

  private AmazonS3 s3Client;

  private final static String TARGET_BUCKET = "test";

  @Before
  public void setupS3Client() {
    s3Client = AmazonS3ClientBuilder.standard()
        .enablePathStyleAccess()
        .disableChunkedEncoding()
        .withEndpointConfiguration(new EndpointConfiguration("http://localhost:4572", Regions.US_EAST_1.getName()))
        .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("x", "x")))
        .build();
    if (!s3Client.doesBucketExist(TARGET_BUCKET)) {
      s3Client.createBucket(TARGET_BUCKET);
    }
  }

  @Test
  public void test() {
    IntStream.range(7, 168).mapToObj(numberOfKBytes -> {
      int sizeInBytes = numberOfKBytes * 1_024;
      byte [] randomData = new byte[sizeInBytes];
      new Random().nextBytes(randomData);
      try {
        File testFile = Files.createTempFile(String.format("%d_", numberOfKBytes), null).toFile();
        FileOutputStream out = new FileOutputStream(testFile);
        out.write(randomData);
        out.close();
        return testFile;
      }
      catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }).forEach(it ->
    {
      System.out.println("Uploading " + it.getAbsolutePath());
      uploadFile(it);
      System.out.println("Uploaded " + it.getAbsolutePath());
      it.delete();
    });

  }

  private void uploadFile(File file){
    String s3Key = this.getClass().getName() + "-" + UUID.randomUUID();
    try {
      s3Client.putObject(TARGET_BUCKET, s3Key, file);
    }
    finally {
      try {
        s3Client.deleteObject(TARGET_BUCKET, s3Key);
      }
      catch (Exception e) {
        System.out.println(String.format("Could not delete test file from s3 [ s3://%s/%s ] %s", TARGET_BUCKET, s3Key, e));
      }
    }
  }
}
