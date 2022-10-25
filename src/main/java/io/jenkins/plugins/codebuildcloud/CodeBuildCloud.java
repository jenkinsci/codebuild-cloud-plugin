package io.jenkins.plugins.codebuildcloud;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import org.yaml.snakeyaml.Yaml;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.codebuild.AWSCodeBuild;
import com.amazonaws.services.codebuild.model.EnvironmentType;
import com.amazonaws.services.codebuild.model.ListProjectsRequest;
import com.amazonaws.services.codebuild.model.ListProjectsResult;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsHelper;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.AbstractIdCredentialsListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.cli.shaded.org.apache.commons.lang.NotImplementedException;
import io.jenkins.cli.shaded.org.apache.commons.lang.NullArgumentException;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;

/*
 * Root class for configuration of cloud for Jenkins.
 * Jenkins calls {@link CodeBuildCloud#provision(Label label, int excessWorkload)} on this
 * class to create new nodes.
 */
public class CodeBuildCloud extends Cloud {

  private static final Logger LOGGER = Logger.getLogger(CodeBuildCloud.class.getName());

  private static final Integer DEFAULT_AGENT_TIMEOUT = 120;
  private static final String DEFAULT_PROTOCOLS = "JNLP4-connect";
  private static final Boolean DEFAULT_NORECONNECT = true;

  // Any nodes you find on bootup, get rid of
  static {
    clearAllNodes();
  }

  // Fields

  @Nonnull
  private String codeBuildProjectName;

  @Nonnull
  private String credentialId;

  @Nonnull
  private String region;

  @Nonnull
  private String label;

  @Nonnull
  private Integer agentTimeout;

  @Nonnull
  private Secret controllerIdentity;

  @Nonnull
  private String direct;

  @Nonnull
  private Boolean disableHttpsCertValidation;

  @Nonnull
  private Boolean noKeepAlive;

  @Nonnull
  private Boolean noReconnect;

  @Nonnull
  private String protocols;

  @Nonnull
  private String proxyCredentialsId;

  @Nonnull
  private String tunnel;

  @Nonnull
  private String url;

  @Nonnull
  private Boolean webSocket;

  @Nonnull
  private String dockerImage;

  @Nonnull
  private String computeType;

  @Nonnull
  private String environmentType;

  @Nonnull
  private String buildSpec;

  @DataBoundConstructor
  public CodeBuildCloud(@Nonnull String name,
      @Nonnull String codeBuildProjectName,
      @Nonnull String credentialId,
      @Nonnull String region,
      @Nonnull String label,
      @Nonnull Integer agentTimeout,
      @Nonnull String dockerImage,
      @Nonnull String computeType,
      @Nonnull String environmentType,
      @Nonnull String buildSpec,

      // JNLP Params
      @Nonnull String direct,
      @Nonnull Boolean disableHttpsCertValidation,
      @Nonnull Boolean noKeepAlive,
      @Nonnull Boolean noReconnect,
      @Nonnull String protocols,
      @Nonnull String proxyCredentialsId,
      @Nonnull String tunnel,
      @Nonnull String url,
      @Nonnull Boolean webSocket) throws NotImplementedException {
    super(StringUtils.isNotBlank(name) ? name : "codebuildcloud_" + getJenkins().clouds.size());

    this.codeBuildProjectName = codeBuildProjectName;
    this.credentialId = credentialId;
    this.region = region;
    this.label = label;
    this.agentTimeout = agentTimeout;
    this.dockerImage = dockerImage;
    this.computeType = computeType;
    this.environmentType = environmentType;
    this.buildSpec = buildSpec;

    // JNLP params
    this.direct = direct;
    this.disableHttpsCertValidation = disableHttpsCertValidation;
    this.noKeepAlive = noKeepAlive;
    this.noReconnect = noReconnect;
    this.protocols = protocols;
    this.proxyCredentialsId = proxyCredentialsId;
    this.tunnel = tunnel;
    this.url = url;
    this.webSocket = webSocket;

    // Needed if skipping HTTPS call
    String myIdentity = InstanceIdentity.get().getEncodedPublicKey();
    if (myIdentity == null) {
      throw new NotImplementedException("Failed to find Jenkins Identity");
    }
    this.controllerIdentity = Secret.fromString(myIdentity);

    LOGGER.info(" Initializing Cloud");
    //logConfig(); //<-- Use this if having trouble with configuration)
  }

  private void logConfig() {
    LOGGER.info("CodeBuild Project Name: " + this.codeBuildProjectName);
    LOGGER.info("CodeBuild credentialId: " + this.credentialId);
    LOGGER.info("CodeBuild region: " + this.region);
    LOGGER.info("CodeBuild label: " + this.label);
    LOGGER.info("CodeBuild agentTimeout: " + this.agentTimeout);
    LOGGER.info("CodeBuild dockerImage: " + this.dockerImage);
    LOGGER.info("CodeBuild computeType: " + this.computeType);
    LOGGER.info("CodeBuild direct: " + this.direct);
    LOGGER.info("CodeBuild disableHttpsCertValidation: " + this.disableHttpsCertValidation);
    LOGGER.info("CodeBuild noKeepAlive: " + this.noKeepAlive);
    LOGGER.info("CodeBuild noReconnect: " + this.noReconnect);
    LOGGER.info("CodeBuild protocols: " + this.protocols);
    LOGGER.info("CodeBuild proxyCredentialsId: " + this.proxyCredentialsId);
    LOGGER.info("CodeBuild tunnel: " + this.tunnel);
    LOGGER.info("CodeBuild url: " + this.url);
    LOGGER.info("CodeBuild webSocket: " + this.webSocket);
    LOGGER.info("CodeBuild controllerIdentity: " + this.controllerIdentity.getPlainText());
    LOGGER.info("CodeBuild environmentType: " + this.environmentType);
    LOGGER.info("CodeBuild buildSpec: " + this.buildSpec);
  }

  /**
   * Clear all nodes on boot-up because they shouldnt be permanent.
   */
  private static void clearAllNodes() {
    List<Node> nodes = getJenkins().getNodes();
    if (nodes.size() == 0) {
      return;
    }

    LOGGER.info("Clearing all previous  nodes...");
    for (final Node n : nodes) {
      if (n instanceof CodeBuildSlave) {
        try {
          ((CodeBuildSlave) n).terminate();
        } catch (InterruptedException | IOException e) {
          LOGGER.log(Level.SEVERE, String.format("Failed to terminate agent '%s'", n.getDisplayName()), e);
        }
      }
    }
  }

  @Nonnull
  protected static Jenkins getJenkins() {
    Jenkins instance = Jenkins.getInstanceOrNull();
    if (instance == null) {
      throw new NullArgumentException("Jenkins is null");
    }
    return instance;
  }

  // Getters and setters

  public String getName() {
    return name;
  }

  @Nonnull
  public String getCodeBuildProjectName() {
    return codeBuildProjectName;
  }

  @DataBoundSetter
  public void setCodeBuildProjectName(String codeBuildProjectName) {
    this.codeBuildProjectName = codeBuildProjectName;
  }

  @Nonnull
  public String getRegion() {
    return region;
  }

  @DataBoundSetter
  public void setRegion(String region) {
    this.region = region;
  }

  @Nonnull
  public String getLabel() {
    return label;
  }

  @DataBoundSetter
  public void setLabel(String label) {
    this.label = label;
  }

  @Nonnull
  public Integer getAgentTimeout() {
    return agentTimeout;
  }

  @DataBoundSetter
  public void setAgentTimeout(Integer agentTimeout) {
    this.agentTimeout = agentTimeout;
  }

  @Nonnull
  public String getCredentialId() {
    return credentialId;
  }

  @DataBoundSetter
  public void setCredentialId(String credentialId) {
    this.credentialId = credentialId;
  }

  @Nonnull
  public Secret getControllerIdentity() {
    return controllerIdentity;
  }

  @DataBoundSetter
  public void setControllerIdentity(Secret controllerIdentity) {
    this.controllerIdentity = controllerIdentity;
  }

  @Nonnull
  public String getDirect() {
    return direct;
  }

  @DataBoundSetter
  public void setDirect(String direct) {
    this.direct = direct;
  }

  @Nonnull
  public Boolean getDisableHttpsCertValidation() {
    return disableHttpsCertValidation;
  }

  @DataBoundSetter
  public void setDisableHttpsCertValidation(Boolean disableHttpsCertValidation) {
    this.disableHttpsCertValidation = disableHttpsCertValidation;
  }

  @Nonnull
  public Boolean getNoKeepAlive() {
    return noKeepAlive;
  }

  @DataBoundSetter
  public void setNoKeepAlive(Boolean noKeepAlive) {
    this.noKeepAlive = noKeepAlive;
  }

  @Nonnull
  public Boolean getNoReconnect() {
    return noReconnect;
  }

  @DataBoundSetter
  public void setNoReconnect(Boolean noReconnect) {
    this.noReconnect = noReconnect;
  }

  @Nonnull
  public String getProtocols() {
    return protocols;
  }

  @DataBoundSetter
  public void setProtocols(String protocols) {
    this.protocols = protocols;
  }

  @Nonnull
  public String getProxyCredentialsId() {
    return proxyCredentialsId;
  }

  @DataBoundSetter
  public void setProxyCredentialsId(String proxyCredentialsId) {
    this.proxyCredentialsId = proxyCredentialsId;
  }

  @Nonnull
  public String getTunnel() {
    return tunnel;
  }

  @DataBoundSetter
  public void setTunnel(String tunnel) {
    this.tunnel = tunnel;
  }

  @Nonnull
  public String getUrl() {
    return url;
  }

  @DataBoundSetter
  public void setUrl(String url) {
    this.url = url;
  }

  @Nonnull
  public Boolean getWebSocket() {
    return webSocket;
  }

  @DataBoundSetter
  public void setWebSocket(Boolean webSocket) {
    this.webSocket = webSocket;
  }

  @Nonnull
  public String getDockerImage() {
    return dockerImage;
  }

  @DataBoundSetter
  public void setDockerImage(String dockerImage) {
    this.dockerImage = dockerImage;
  }

  @Nonnull
  public String getComputeType() {
    return computeType;
  }

  @DataBoundSetter
  public void setComputeType(String computeType) {
    this.computeType = computeType;
  }

  @Nonnull
  public String getEnvironmentType() {
    return environmentType;
  }

  @DataBoundSetter
  public void setEnvironmentType(String environmentType) {
    this.environmentType = environmentType;
  }

  @Nonnull
  public String getBuildSpec() {
    return buildSpec;
  }

  @DataBoundSetter
  public void setBuildSpec(String buildSpec) {
    this.buildSpec = buildSpec;
  }

  // Implementation methods for provisioning codebuild cloud agents

  private transient long lastProvisionTime = 0; // keep track of to not create too many agents
  private transient CodeBuildClientWrapper client;

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return String.format("%s<%s>", name, codeBuildProjectName);
  }

  /** {@inheritDoc} */
  @Override
  public boolean canProvision(Label label) {
    boolean canProvision = label == null ? true : label.matches(Arrays.asList(new LabelAtom(getLabel())));
    LOGGER.info(String.format("Check provisioning capabilities for label '%s': %s", label, canProvision));
    return canProvision;
  }

  /**
   * Getter for the field <code>client</code>.
   *
   * @return a {@link com.amazonaws.services.codebuild.AWSCodeBuild} object.
   */
  public synchronized CodeBuildClientWrapper getClient() {
    if (this.client == null) {
      this.client = CodeBuildClientWrapperFactory.buildClient(this.credentialId, this.region,
          CodeBuildCloud.getJenkins());
    }
    return this.client;
  }

  /**
   * Find the number of {@link CodeBuildSlave} instances still connecting to
   * Jenkins host.
   */
  private long countStillProvisioning() {
    return getJenkins().getNodes().stream().filter(CodeBuildSlave.class::isInstance).map(CodeBuildSlave.class::cast)
        .filter(a -> a.getLauncher().isLaunchSupported()).count();
  }

  /** {@inheritDoc} */
  @Override
  public synchronized Collection<PlannedNode> provision(Label label, int excessWorkload) {
    List<NodeProvisioner.PlannedNode> list = new ArrayList<NodeProvisioner.PlannedNode>();

    // guard against non-matching labels
    if (label != null && !label.matches(Arrays.asList(new LabelAtom(getLabel())))) {
      return list;
    }

    // guard against double-provisioning with a 500ms cooldown clock
    long timeDiff = System.currentTimeMillis() - lastProvisionTime;
    if (timeDiff < 500) {
      LOGGER.info(String.format("Provision of %s skipped, still on cooldown %sms of 500ms)", excessWorkload, timeDiff));
      return list;
    }

    // If we reach here its time to provision. This is because the label matches and
    // the cooldown period has been hit.

    String labelName = label == null ? getLabel() : label.getDisplayName();
    long stillProvisioning = countStillProvisioning();
    long numToLaunch = Math.max(excessWorkload - stillProvisioning, 0);
    LOGGER.info(String.format("Provisioning %s nodes for label '%s' (%s already provisioning)", numToLaunch, labelName,
        stillProvisioning));

    for (int i = 0; i < numToLaunch; i++) {

      // Unique node names
      final String suffix = RandomStringUtils.randomAlphabetic(4);
      final String displayName = String.format("%s.cb-%s", codeBuildProjectName, suffix);

      final CodeBuildCloud cloud = this;
      final Future<Node> nodeResolver = Computer.threadPoolForRemoting.submit(() -> {
        CodeBuildLauncher launcher = new CodeBuildLauncher(cloud);
        CodeBuildSlave agent = new CodeBuildSlave(displayName, cloud, launcher);
        getJenkins().addNode(agent);
        return agent;
      });
      list.add(new NodeProvisioner.PlannedNode(displayName, nodeResolver, 1));
    }

    lastProvisionTime = System.currentTimeMillis();
    return list;

  }

  @Extension
  public static class DescriptorImpl extends Descriptor<Cloud> {

    @POST
    public ListBoxModel doFillCredentialIdItems(@QueryParameter String value) {
      if (getJenkins().hasPermission(Jenkins.ADMINISTER)) {
        return AWSCredentialsHelper.doFillCredentialsIdItems(getJenkins());
      }
      else{
        ListBoxModel model =  new ListBoxModel();
        model.add(value);
        return model;
      }
    }


    @POST
    public ListBoxModel doFillProxyCredentialsIdItems(@QueryParameter String value) {
      if (getJenkins().hasPermission(Jenkins.ADMINISTER)) {

        AbstractIdCredentialsListBoxModel result = new StandardListBoxModel().includeEmptyValue();

        result = result.withMatching(
                            CredentialsMatchers.always(),
                            CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class,
                                  getJenkins(),
                                    ACL.SYSTEM,
                                    Collections.EMPTY_LIST));
      return result;
                                    
      }
      else{
        return new StandardUsernameListBoxModel().includeCurrentValue(value);
      }      
    }

    @POST
    public ListBoxModel doFillRegionItems() {

      final ListBoxModel options = new ListBoxModel();

      // NO AWS API Calls here
      for (Region r : RegionUtils.getRegionsForService(AWSCodeBuild.ENDPOINT_PREFIX)) {
        options.add(r.getName());
      }
      return options;
    }

    @POST
    public ListBoxModel doFillCodeBuildProjectNameItems(@QueryParameter String credentialId,
        @QueryParameter String region) {

      final ListBoxModel options = new ListBoxModel();

      // List of projects from Codebuild
      final List<String> codebuildProjects = new ArrayList<String>();

      CodeBuildClientWrapper client = CodeBuildClientWrapperFactory.buildClient(credentialId, region, getJenkins());
      try {
        String nextToken = null;
        do {
          ListProjectsResult result = client.listProjects(new ListProjectsRequest().withNextToken(nextToken));
          codebuildProjects.addAll(result.getProjects());
          nextToken = result.getNextToken();
        } while (nextToken != null);
      } catch (com.amazonaws.SdkClientException e) {
        if (e.getMessage().contains("Unable to load AWS credentials")) {
          LOGGER.warning(
              " Exception listing codebuild project because of no valid AWS credentials. Exception: " + e.toString());
          return options;
        } else if (e.getMessage().contains("The security token included in the request is invalid")) {
          LOGGER.warning(
              " Exception listing codebuild project because of INVALID AWS credentials Exception: " + e.toString());
          return options;
        } else {
          throw e;
        }
      }

      // Sort them
      Collections.sort(codebuildProjects);

      // Add to options
      for (String item : codebuildProjects) {
        options.add(item);
      }
      return options;
    }

    @POST
    public FormValidation doCheckBuildSpec(@QueryParameter String value) {

      // Validate its correct YAML at least
      try {
        new Yaml().load(value);
        return FormValidation.ok();
      } catch (Exception e) {
        return FormValidation.error("Incorrect YAML DEFINITION: " + e.toString());
      }
    }

    @POST
    public FormValidation doCheckCodeBuildProjectName(@QueryParameter String value) {
      if (value.length() == 0) {
        return FormValidation.error("Please select a Codebuild Project");
      }
      return FormValidation.ok();
    }

    @POST
    public ListBoxModel doFillEnvironmentTypeItems() {
      final ListBoxModel options = new ListBoxModel();
      // From here:
      // https://docs.aws.amazon.com/en_us/AWSJavaSDK/latest/javadoc/com/amazonaws/services/codebuild/model/EnvironmentType.html

      for (EnvironmentType theValue : com.amazonaws.services.codebuild.model.EnvironmentType.values()) {
        options.add(theValue.name());
      }

      return options;
    }

    @POST
    public ListBoxModel doFillComputeTypeItems() {
      final ListBoxModel options = new ListBoxModel();
      // From here:
      // https://docs.aws.amazon.com/AWSJavaScriptSDK/v3/latest/clients/client-codebuild/enums/computetype.html
      // And here
      // https://docs.aws.amazon.com/codebuild/latest/userguide/build-env-ref-compute-types.html
      options.add("BUILD_GENERAL1_SMALL");
      options.add("BUILD_GENERAL1_MEDIUM");
      options.add("BUILD_GENERAL1_LARGE");
      options.add("BUILD_GENERAL1_2XLARGE");

      return options;
    }

    // Special naming convention that makes jelly work get****
    @POST
    public Integer getDefaultAgentTimeout() {
      return DEFAULT_AGENT_TIMEOUT;
    }

    @POST
    public String getDefaultUrl() {
      JenkinsLocationConfiguration config = JenkinsLocationConfiguration.get();
      return StringUtils.defaultIfBlank(config.getUrl(), "unknown");
    }

    @POST
    public String getDefaultProtocols() {
      return DEFAULT_PROTOCOLS;
    }

    @POST
    public Boolean getDefaultNoReconnect() {
      return DEFAULT_NORECONNECT;
    }

    @Override
    public String getDisplayName() {
      return Messages.CodeBuildCloud_DescriptorImpl_DisplayName();
    }

  }

}
