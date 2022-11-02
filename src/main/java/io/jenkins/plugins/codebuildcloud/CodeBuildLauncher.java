package io.jenkins.plugins.codebuildcloud;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import com.amazonaws.services.codebuild.model.EnvironmentVariable;
import com.amazonaws.services.codebuild.model.SourceType;
import com.amazonaws.services.codebuild.model.StartBuildRequest;
import com.amazonaws.services.codebuild.model.StartBuildResult;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.StreamTaskListener;
import io.jenkins.cli.shaded.org.apache.commons.lang.StringUtils;
import io.jenkins.plugins.codebuildcloud.CodeBuildClientWrapper.CodeBuildStatus;

public class CodeBuildLauncher extends JNLPLauncher {
  private static final int sleepMs = 500;
  private static final Logger LOGGER = Logger.getLogger(CodeBuildLauncher.class.getName());
  private static final int CHECK_WITH_CODEBUILD_STATUS = Math.multiplyExact(30, 1000);

  private final CodeBuildCloud cloud;
  private boolean launched;

  public CodeBuildLauncher(CodeBuildCloud cloud) {
    super(true);
    this.cloud = cloud;
  }

  /** {@inheritDoc} */
  @Override
  public boolean isLaunchSupported() {
    return !launched;
  }

  /** {@inheritDoc} */
  @Override
  public void launch(@NonNull SlaveComputer computer, @NonNull TaskListener listener) {
    this.launched = false;
    if (!(computer instanceof CodeBuildComputer)) {
      LOGGER.finest(String.format("Not launching %s since it is not the correct type (%s)", computer,
          CodeBuildComputer.class.getName()));
      return;
    }

    Node node = computer.getNode();
    if (node == null) {
      LOGGER.severe(String.format("Not launching %s since it is missing a node.", computer.getName()));
      return;
    }

    LOGGER.info(String.format("Launching %s with %s", computer, listener));
    CodeBuildComputer codebuildComputer = (CodeBuildComputer) computer;

    // Extra ENV Variables to add to the
    List<EnvironmentVariable> myenvcollection = buildEnvVariableCollection(computer, node);

    StartBuildRequest req = new StartBuildRequest()
        .withProjectName(cloud.getCodeBuildProjectName())
        .withSourceTypeOverride(SourceType.NO_SOURCE)
        .withImageOverride(cloud.getDockerImage())
        .withEnvironmentTypeOverride(cloud.getEnvironmentType())
        .withPrivilegedModeOverride(true)
        .withEnvironmentVariablesOverride(myenvcollection)
        .withComputeTypeOverride(cloud.getComputeType())
        .withBuildspecOverride(cloud.getBuildSpec());

    String buildId = null;
    try {
      StartBuildResult res = cloud.getClient().startBuild(req);
      buildId = res.getBuild().getId();
      codebuildComputer.setBuildId(buildId);

      waitForAgentConnection(computer, buildId, node);

    } catch (Exception e) {

      if (e instanceof TimeoutException && buildId != null) {
        // Stop the build or make sure stopped
        cloud.getClient().stopBuild(buildId);
      }

      codebuildComputer.setBuildId(null);
      LOGGER.severe(String.format("Exception while starting build: %s.  Exception %s", e.getMessage(), e));
      listener.fatalError("Exception while starting build: %s", e.getMessage());

      if (node instanceof CodeBuildAgent) {
        try {
          CodeBuildCloud.getJenkins().removeNode(node);
        } catch (IOException e1) {
          LOGGER.severe(String.format("Failed to terminate agent: %s.  Exception: %s", node.getDisplayName(), e));
        }
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public void beforeDisconnect(@NonNull SlaveComputer computer, @NonNull StreamTaskListener listener) {
    if (computer instanceof CodeBuildComputer) {
      ((CodeBuildComputer) computer).setBuildId(null);
    }
  }

  private void waitForAgentConnection(@NonNull SlaveComputer computer, @NonNull String buildId, @NonNull Node node)
      throws TimeoutException, InvalidObjectException, InterruptedException {
    LOGGER.info(String.format("Waiting for agent '%s' to connect to build ID: %s...", computer, buildId));

    int checkbuildcounter = 0;
    for (int i = 0; i < cloud.getAgentTimeout() * (1000 / sleepMs); i++) {
      if (computer.isOnline() && computer.isAcceptingTasks()) {
        LOGGER.info(String.format(" Agent '%s' connected to build ID: %s.", computer, buildId));
        launched = true;
        return;
      }
      Thread.sleep(sleepMs);
      checkbuildcounter += sleepMs;

      // Has it been 30 second or longer? Run request to ask Codebuild status of the
      // build. This allows us to fail fast on this side of the connection.
      if (checkbuildcounter > 30000) {
        checkbuildcounter = 0; // Reset

        // Should be inprogress only at this point.
        cloud.getClient().checkBuildStatus(buildId, Arrays.asList(CodeBuildStatus.FAILED,
            CodeBuildStatus.FAULT,
            CodeBuildStatus.STOPPED,
            CodeBuildStatus.SUCCEEDED,
            CodeBuildStatus.TIMED_OUT));
      }
    }
    throw new TimeoutException("Timed out while waiting for agent " + node + " to start for build ID: " + buildId);
  }

  private String lookupProxyCredentials() {
    String proxyCredentialId = cloud.getProxyCredentialsId();
    String proxyCredentials = null;
    if (!StringUtils.isBlank(proxyCredentialId)) {

      Credentials c = CredentialsMatchers.firstOrNull(
          CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class,
              cloud.getJenkins(),
              ACL.SYSTEM,
              Collections.EMPTY_LIST),
          CredentialsMatchers.withId(proxyCredentialId));

      // LOGGER.info("credentials: "+c.toString());

      if (c != null && c instanceof StandardUsernamePasswordCredentials) {
        StandardUsernamePasswordCredentials mycreds = (StandardUsernamePasswordCredentials) c;
        proxyCredentials = mycreds.getUsername() + ":" + mycreds.getPassword().getPlainText();
        // LOGGER.info("Proxy Credentials:" + proxyCredentials);
      }
    }

    return proxyCredentials;
  }

  private List<EnvironmentVariable> buildEnvVariableCollection(@NonNull SlaveComputer computer, @NonNull Node node) {
    List<EnvironmentVariable> mylist = new ArrayList<EnvironmentVariable>();

    String proxyCredentials = lookupProxyCredentials();

    // Next section based on below script and my own design for buildspec files
    // https://github.com/jenkinsci/docker-inbound-agent/blob/62ee56932623a0a66179b0130da806c39d5c323f/jenkins-agent#L27-L39

    // 3 primary use cases
    // Direct
    // Websocket
    // Everything else

    // Direct use case
    if (StringUtils.isNotEmpty(cloud.getDirect())) {
      // Cannot include URL or Tunnel forbids = {"-url", "-tunnel"}

      mylist.add(createEnvVariable("JENKINS_DIRECT_CONNECTION", cloud.getDirect()));
      mylist.add(createEnvVariable("JENKINS_INSTANCE_IDENTITY", cloud.getControllerIdentity().getPlainText()));

      if (StringUtils.isNotEmpty(cloud.getProtocols())) {
        mylist.add(createEnvVariable("JENKINS_PROTOCOLS", cloud.getProtocols()));
      }

      if (StringUtils.isNotEmpty(proxyCredentials)) {
        mylist.add(createEnvVariable("JENKINS_CODEBUILD_PROXY_CREDENTIALS",
            "-proxyCredentials " + proxyCredentials));
      }

      if (cloud.getNoKeepAlive()) {
        mylist.add(createEnvVariable("JENKINS_CODEBUILD_NOKEEPALIVE", "-noKeepAlive"));
      }

      if (cloud.getDisableHttpsCertValidation()) {
        mylist.add(createEnvVariable("JENKINS_CODEBUILD_DISABLE_SSL_VALIDATION", "-disableHttpsCertValidation"));
      }
    } else if (cloud.getWebSocket()) { // websocket use case
      mylist.add(createEnvVariable("JENKINS_WEB_SOCKET", "true"));
      mylist.add(createEnvVariable("JENKINS_URL", cloud.getUrl()));
    } else {
      if (StringUtils.isNotEmpty(cloud.getTunnel())) {
        mylist.add(createEnvVariable("JENKINS_TUNNEL", cloud.getTunnel()));
      }

      mylist.add(createEnvVariable("JENKINS_URL", cloud.getUrl()));

      if (StringUtils.isNotEmpty(proxyCredentials)) {
        mylist.add(createEnvVariable("JENKINS_CODEBUILD_PROXY_CREDENTIALS",
            "-proxyCredentials " + proxyCredentials));
      }

      if (cloud.getNoKeepAlive()) {
        mylist.add(createEnvVariable("JENKINS_CODEBUILD_NOKEEPALIVE", "-noKeepAlive"));
      }

      if (cloud.getDisableHttpsCertValidation()) {
        mylist.add(createEnvVariable("JENKINS_CODEBUILD_DISABLE_SSL_VALIDATION", "-disableHttpsCertValidation"));
      }
    }

    // no forbids or dependencies - all agent types get them
    if (cloud.getNoReconnect()) {
      mylist.add(createEnvVariable("JENKINS_CODEBUILD_NORECONNECT", "-noreconnect"));
    }
    mylist.add(createEnvVariable("JENKINS_SECRET", computer.getJnlpMac()));
    mylist.add(createEnvVariable("JENKINS_AGENT_NAME", node.getDisplayName()));

    // Extra helper environment variables for downloading the JAR file instead of
    // over the internet
    try {
      java.net.URL myurl = new java.net.URL(cloud.getUrl());

      mylist.add(createEnvVariable("JENKINS_CODEBUILD_AGENT_URL",
          myurl.getProtocol() + "://" + myurl.getAuthority() + "/jnlpJars/agent.jar"));
    } catch (MalformedURLException e) {
      mylist.add(createEnvVariable("JENKINS_CODEBUILD_AGENT_URL", "ERROR"));
    }

    return mylist;
  }

  private EnvironmentVariable createEnvVariable(String key, String value) {
    EnvironmentVariable var1 = new EnvironmentVariable();
    var1.setName(key);
    var1.setType("PLAINTEXT");
    var1.setValue(value);

    return var1;

  }
}
