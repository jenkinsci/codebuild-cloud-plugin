package io.jenkins.plugins.codebuildcloud;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.amazonaws.SdkClientException;
import com.amazonaws.regions.DefaultAwsRegionProviderChain;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.codebuild.AWSCodeBuild;
import com.amazonaws.services.codebuild.model.EnvironmentType;
import com.amazonaws.services.codebuild.model.ImagePullCredentialsType;
import com.amazonaws.services.codebuild.model.ListProjectsRequest;
import com.amazonaws.services.codebuild.model.ListProjectsResult;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsHelper;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
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
import jakarta.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;

/*
 * Root class for configuration of cloud for Jenkins.
 * Jenkins calls {@link CodeBuildCloud#provision(Label label, int excessWorkload)} on this
 * class to create new nodes.
 */
public class CodeBuildCloud extends Cloud {

  private static final Logger LOGGER = Logger.getLogger(CodeBuildCloud.class.getName());

  private static final Integer DEFAULT_AGENT_CONNECT_TIMEOUT = 180;
  private static final Integer DEFAULT_MAX_AGENTS = 50;
  private static final String DEFAULT_PROTOCOLS = "JNLP4-connect";
  private static final Boolean DEFAULT_NORECONNECT = true;

  // Any nodes you find on bootup, get rid of
  static {
    clearAllNodes();
  }

  // Fields

  @NonNull
  private String codeBuildProjectName;

  @NonNull
  private String credentialId;

  @NonNull
  private String region;

  @NonNull
  private String label;

  @NonNull
  private Integer agentConnectTimeout;

  @NonNull
  private Secret controllerIdentity;

  @NonNull
  private String direct;

  @NonNull
  private Boolean verifyIsCodeBuildIPOnJNLP;

  @NonNull
  private Boolean disableHttpsCertValidation;

  @NonNull
  private Boolean noKeepAlive;

  @NonNull
  private Boolean noReconnect;

  @NonNull
  private String protocols;

  @NonNull
  private String proxyCredentialsId;

  @NonNull
  private String tunnel;

  @NonNull
  private String url;

  @NonNull
  private Boolean webSocket;

  @NonNull
  private String dockerImage;

  @NonNull
  private String computeType;

  @NonNull
  private String environmentType;

  @NonNull
  private String buildSpec;

  @NonNull
  private String dockerImagePullCredentials;

  @Nonnull
  private Integer maxAgents;

  @DataBoundConstructor
  public CodeBuildCloud(@NonNull String name,
      @NonNull String codeBuildProjectName,
      @NonNull String credentialId,
      @NonNull String region,
      @NonNull String label,
      @NonNull Integer agentConnectTimeout,
      @NonNull String dockerImage,
      @NonNull String dockerImagePullCredentials,
      @NonNull String computeType,
      @NonNull String environmentType,
      @NonNull String buildSpec,
      @NonNull Boolean verifyIsCodeBuildIPOnJNLP,
      @NonNull Integer maxAgents,

      // JNLP Params
      @NonNull String direct,
      @NonNull Boolean disableHttpsCertValidation,
      @NonNull Boolean noKeepAlive,
      @NonNull Boolean noReconnect,
      @NonNull String protocols,
      @NonNull String proxyCredentialsId,
      @NonNull String tunnel,
      @NonNull String url,
      @NonNull Boolean webSocket) throws NotImplementedException {
    super(StringUtils.isNotBlank(name) ? name : "cbc-" + label);

    this.codeBuildProjectName = codeBuildProjectName;
    this.credentialId = credentialId;
    this.region = region;
    this.label = label;
    this.agentConnectTimeout = agentConnectTimeout;
    this.dockerImage = dockerImage;
    this.computeType = computeType;
    this.environmentType = environmentType;
    this.buildSpec = buildSpec;
    this.dockerImagePullCredentials = dockerImagePullCredentials;
    this.verifyIsCodeBuildIPOnJNLP = verifyIsCodeBuildIPOnJNLP;
    this.maxAgents = maxAgents;

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

    if (maxAgents == null || maxAgents == 0) {
      this.maxAgents = DEFAULT_MAX_AGENTS;
    }

    LOGGER.info(" Initializing Cloud");
    // logConfig(); //<-- Use this if having trouble with configuration)
  }

  private void logConfig() {
    LOGGER.info("CodeBuild Project Name: " + this.codeBuildProjectName);
    LOGGER.info("CodeBuild credentialId: " + this.credentialId);
    LOGGER.info("CodeBuild region: " + this.region);
    LOGGER.info("CodeBuild label: " + this.label);
    LOGGER.info("CodeBuild agentTimeout: " + this.agentConnectTimeout);
    LOGGER.info("CodeBuild dockerImage: " + this.dockerImage);
    LOGGER.info("CodeBuild dockerImagePullCredentials: " + this.dockerImagePullCredentials);
    LOGGER.info("CodeBuild verifyIsCodeBuildIPOnJNLP: " + this.verifyIsCodeBuildIPOnJNLP);
    LOGGER.info("Codebuild maxAgents:" + maxAgents);
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
      if (n instanceof CodeBuildAgent) {
        try {
          ((CodeBuildAgent) n).terminate();
        } catch (InterruptedException | IOException e) {
          LOGGER.log(Level.SEVERE, String.format("Failed to terminate agent '%s'", n.getDisplayName()), e);
        }
      }
    }
  }

  @NonNull
  protected static Jenkins getJenkins() {
    Jenkins instance = Jenkins.get();
    if (instance == null) {
      throw new NullArgumentException("Jenkins is null");
    }
    return instance;
  }

  // Getters and setters

  public String getName() {
    return name;
  }

  @NonNull
  public String getCodeBuildProjectName() {
    return codeBuildProjectName;
  }

  @DataBoundSetter
  public void setCodeBuildProjectName(String codeBuildProjectName) {
    this.codeBuildProjectName = codeBuildProjectName;
  }

  @NonNull
  public String getRegion() {
    return region;
  }

  @DataBoundSetter
  public void setRegion(String region) {
    this.region = region;
  }

  @NonNull
  public String getLabel() {
    return label;
  }

  @DataBoundSetter
  public void setLabel(String label) {
    this.label = label;
  }

  @NonNull
  public Integer getAgentConnectTimeout() {
    return agentConnectTimeout;
  }

  @DataBoundSetter
  public void setAgentConnectTimeout(Integer agentTimeout) {
    this.agentConnectTimeout = agentTimeout;
  }

  @NonNull
  public String getCredentialId() {
    return credentialId;
  }

  @DataBoundSetter
  public void setCredentialId(String credentialId) {
    this.credentialId = credentialId;
  }

  @NonNull
  public Secret getControllerIdentity() {
    return controllerIdentity;
  }

  @DataBoundSetter
  public void setControllerIdentity(Secret controllerIdentity) {
    this.controllerIdentity = controllerIdentity;
  }

  @NonNull
  public Integer getMaxAgents() {
    return maxAgents;
  }

  @DataBoundSetter
  public void setMaxAgents(Integer maxAgents) {
    this.maxAgents = maxAgents;
  }

  @NonNull
  public String getDirect() {
    return direct;
  }

  @DataBoundSetter
  public void setDirect(String direct) {
    this.direct = direct;
  }

  @NonNull
  public Boolean getDisableHttpsCertValidation() {
    return disableHttpsCertValidation;
  }

  @DataBoundSetter
  public void setDisableHttpsCertValidation(Boolean disableHttpsCertValidation) {
    this.disableHttpsCertValidation = disableHttpsCertValidation;
  }

  @NonNull
  public Boolean getNoKeepAlive() {
    return noKeepAlive;
  }

  @DataBoundSetter
  public void setNoKeepAlive(Boolean noKeepAlive) {
    this.noKeepAlive = noKeepAlive;
  }

  @NonNull
  public Boolean getNoReconnect() {
    return noReconnect;
  }

  @DataBoundSetter
  public void setNoReconnect(Boolean noReconnect) {
    this.noReconnect = noReconnect;
  }

  @NonNull
  public String getProtocols() {
    return protocols;
  }

  @DataBoundSetter
  public void setProtocols(String protocols) {
    this.protocols = protocols;
  }

  @NonNull
  public String getProxyCredentialsId() {
    return proxyCredentialsId;
  }

  @DataBoundSetter
  public void setProxyCredentialsId(String proxyCredentialsId) {
    this.proxyCredentialsId = proxyCredentialsId;
  }

  @NonNull
  public String getTunnel() {
    return tunnel;
  }

  @DataBoundSetter
  public void setTunnel(String tunnel) {
    this.tunnel = tunnel;
  }

  @NonNull
  public String getUrl() {
    return url;
  }

  @DataBoundSetter
  public void setUrl(String url) {
    this.url = url;
  }

  @NonNull
  public Boolean getWebSocket() {
    return webSocket;
  }

  @DataBoundSetter
  public void setWebSocket(Boolean webSocket) {
    this.webSocket = webSocket;
  }

  @NonNull
  public Boolean getVerifyIsCodeBuildIPOnJNLP() {
    return verifyIsCodeBuildIPOnJNLP;
  }

  @DataBoundSetter
  public void setVerifyIsCodeBuildIPOnJNLP(Boolean verifyIsCodeBuildIPOnJNLP) {
    this.verifyIsCodeBuildIPOnJNLP = verifyIsCodeBuildIPOnJNLP;
  }

  @NonNull
  public String getDockerImage() {
    return dockerImage;
  }

  @DataBoundSetter
  public void setDockerImage(String dockerImage) {
    this.dockerImage = dockerImage;
  }

  @NonNull
  public String getDockerImagePullCredentials() {
    return dockerImagePullCredentials;
  }

  @DataBoundSetter
  public void setDockerImagePullCredentials(String dockerImagePullCredentials) {
    this.dockerImagePullCredentials = dockerImagePullCredentials;
  }

  @NonNull
  public String getComputeType() {
    return computeType;
  }

  @DataBoundSetter
  public void setComputeType(String computeType) {
    this.computeType = computeType;
  }

  @NonNull
  public String getEnvironmentType() {
    return environmentType;
  }

  @DataBoundSetter
  public void setEnvironmentType(String environmentType) {
    this.environmentType = environmentType;
  }

  @NonNull
  public String getBuildSpec() {
    return buildSpec;
  }

  @DataBoundSetter
  public void setBuildSpec(String buildSpec) {
    this.buildSpec = buildSpec;
  }

  // Implementation methods for provisioning codebuild cloud agents

  private transient long lastProvisionTime = 0; // keep track of to not create too many agents

  private long getLastProvisionTime() {
    LOGGER.finest("Current Provision time: " + String.valueOf(lastProvisionTime));
    return lastProvisionTime;
  }

  private void setLastProvisionTime(long provisionTime) {
    LOGGER.finest("Setting Provision time: " + String.valueOf(provisionTime));
    lastProvisionTime = provisionTime;
  }

  private transient CodeBuildClientWrapper client;

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return String.format("%s<%s>", name, codeBuildProjectName);
  }

  /** {@inheritDoc} */
  @Override
  public boolean canProvision(Label label) {
    boolean canProv = false;
    if (label != null) {
      canProv = label.matches(Arrays.asList(new LabelAtom(getLabel())));
    }

    LOGGER.finest(String.format("Check provisioning capabilities for label '%s': %s", label, canProv));
    return canProv;
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
   * Find the number of {@link CodeBuildAgent} instances still connecting to
   * Jenkins host.
   */
  private long countStillProvisioning() {

    long mycount = 0;

    for (Node s : getJenkins().getNodes()) {
      if (s instanceof CodeBuildAgent) {
        CodeBuildAgent d = (CodeBuildAgent) s;
        if (d.cloud.equals(this)) {
          if (!d.terminated) { // Even if node still exists and not cleaned up yet - time to not count it since
                               // its on its way out.
            if (d.getLauncher().isLaunchSupported()) {
              // Still launching - means still provisioning
              mycount += 1;
            } else {
              // Provisioned and in use - not still provisiong
            }
          }
        }
      }
    }
    return mycount;
  }

  private long totalProvisionedOrProvisioning() {

    long mycount = 0;

    // LOGGER.info("Count of Nodes: " + getJenkins().getNodes().size());
    for (Node s : getJenkins().getNodes()) {
      if (s instanceof CodeBuildAgent) {
        CodeBuildAgent d = (CodeBuildAgent) s;
        if (d.cloud.equals(this)) {
          if (!d.terminated) { // if not terminated - assume is a running codebuild project
            mycount += 1;
          }
        }
      }
    }
    return mycount;
  }

  private long totalCanProvision() {

    // Calculating here if CodeBuild Project is configured to limit
    long totalConcurrentJobsPossibleFromCBP = getClient().getMaxConcurrentJobs(codeBuildProjectName);
    long totalProvisioned = totalProvisionedOrProvisioning();
    LOGGER.finest("Total concurrent jobs from CB: " + totalConcurrentJobsPossibleFromCBP);
    LOGGER.finest("Total concurrent jobs running/provisioning right now: " + totalProvisioned);

    long totalPossibleToProvisionFromCB = totalConcurrentJobsPossibleFromCBP - totalProvisioned;
    long totalPossibleToProvisionFromPlugin = getMaxAgents() - totalProvisioned;

    // Who wins the codebuild project or the plugin config? Which ever one is lower
    // The lower one wins due to :
    // If its CB - our APIs will fail with 429's.
    // If its maxAgents - the user has configured no more than N agents for this
    // cloud config.
    return Math.min(totalPossibleToProvisionFromCB, totalPossibleToProvisionFromPlugin);

  }

  /** {@inheritDoc} */
  @Override
  public synchronized Collection<PlannedNode> provision(Label label, int excessWorkload) {
    List<NodeProvisioner.PlannedNode> list = new ArrayList<NodeProvisioner.PlannedNode>();

    // guard against non-matching labels
    if (!canProvision(label)) {
      return list;
    }

    // guard against too many provisioned based on CodeBuild project settings or End
    // user plugin settings
    long totalPossibleToProvision = totalCanProvision();
    if (totalPossibleToProvision <= 0) {
      LOGGER.finest(
          String.format(
              "Cannot provision, detected our maximum possible to provision is <= 0 currently: %s.)",
              totalPossibleToProvision));
      return list;
    }

    // guard against double-provisioning with a 5 second cooldown clock (Should be
    // more than enough)
    long timeDiff = System.currentTimeMillis() - getLastProvisionTime();
    LOGGER.finest("Diff in provison time: " + String.valueOf(timeDiff));
    if (timeDiff < 5000) {
      LOGGER.finest(
          String.format("Provision of %s skipped, still on cooldown %sms of 5 seconds)", excessWorkload, timeDiff));
      return list;
    }

    // If we reach here its time to provision. This is because the label matches and
    // the cooldown period has been hit. If Jenkins still thinks there is excess
    // workload - go create it.
    // We take min here since no matter which case we have - we want the minimum
    // number to launch.
    long numToLaunch = Math.min(totalPossibleToProvision, excessWorkload);

    if (numToLaunch == 0) {
      LOGGER.finest(
          String.format("Provision of excess workload (%s) skipped, total can launch is 0", excessWorkload));
      return list; // Skip setting last provision time. Shouldnt apply since we didnt provision
                   // anything.
    }

    String labelName = label == null ? getLabel() : label.getDisplayName();
    LOGGER.info(String.format("Provisioning %s nodes for label '%s' (%s already provisioning)", numToLaunch, labelName,
        countStillProvisioning()));

    for (int i = 0; i < numToLaunch; i++) {

      // Unique node names
      final String suffix = RandomStringUtils.randomAlphabetic(4);
      final String displayName = String.format("%s.%s", name, suffix);

      final CodeBuildCloud cloud = this;
      final Future<Node> nodeResolver = Computer.threadPoolForRemoting.submit(() -> {
        CodeBuildLauncher launcher = new CodeBuildLauncher(cloud);
        CodeBuildAgent agent = new CodeBuildAgent(displayName, cloud, launcher);
        getJenkins().addNode(agent);
        return agent;
      });
      list.add(new NodeProvisioner.PlannedNode(displayName, nodeResolver, 1));
    }

    setLastProvisionTime(System.currentTimeMillis());
    return list;

  }

  @Extension
  public static class DescriptorImpl extends Descriptor<Cloud> {

    private FormValidation checkValue(String value, String error) {
      getJenkins().checkPermission(Jenkins.ADMINISTER);
      if (value.length() != 0) {
        return FormValidation.ok();
      }
      return FormValidation.error(error);
    }

    private FormValidation checkValue(String value, Integer min, Integer max, String error) {
      getJenkins().checkPermission(Jenkins.ADMINISTER);

      try {
        Integer newval = Integer.parseInt(value);
        if (newval <= max && newval >= min) {
          return FormValidation.ok();
        } else {
          return FormValidation
              .error(error + ": Was outside of bounds of allowed values.  Min: " + min + " Max: " + max);
        }
      } catch (Exception e) {
        return FormValidation.error(error + "  Exception: " + e.toString());
      }
    }

    @POST
    public ListBoxModel doFillCredentialIdItems(@AncestorInPath ItemGroup context) {
      getJenkins().checkPermission(Jenkins.ADMINISTER);
      return AWSCredentialsHelper.doFillCredentialsIdItems(context);
    }

    @POST
    public FormValidation doCheckLabel(@QueryParameter String value) {
      return checkValue(value, "Must include a label");
    }

    @POST
    public FormValidation doCheckCredentialId(@QueryParameter String value) {
      // Not performing this check - Jenkins might be running as a role, just check
      // Admin. Also user flipping selection back and forth is valid
      // return checkValue(value, "Please select a valid AWS c");
      getJenkins().checkPermission(Jenkins.ADMINISTER);
      return FormValidation.ok();
    }

    @POST
    public FormValidation doCheckProxyCredentialsId(@QueryParameter String value) {
      // Not performing this check - User flipping selection back and forth is valid
      // return checkValue(value, "Please select a valid AWS c");
      getJenkins().checkPermission(Jenkins.ADMINISTER);
      return FormValidation.ok();
    }

    @POST
    public ListBoxModel doFillDockerImagePullCredentialsItems() {
      getJenkins().checkPermission(Jenkins.ADMINISTER);

      final StandardListBoxModel options = new StandardListBoxModel();
      options.includeEmptyValue();

      // NO AWS API Calls here
      for (ImagePullCredentialsType thetype : ImagePullCredentialsType.values()) {
        options.add(thetype.name());
      }
      return options;
    }

    @POST
    public FormValidation doCheckDockerImage(@QueryParameter String value) {
      return checkValue(value, "Must put in a valid docker image string");
    }

    @POST
    public FormValidation doCheckDockerImagePullCredentials(@QueryParameter String value) {
      return checkValue(value, "Must pick the Credential Type to pull the image for AWS CodeBuild service.");
    }

    @POST
    public ListBoxModel doFillProxyCredentialsIdItems(@QueryParameter String value) {
      getJenkins().checkPermission(Jenkins.ADMINISTER);

      ListBoxModel result = new StandardUsernameListBoxModel()
          .includeEmptyValue()
          .includeAs(ACL.SYSTEM, getJenkins(), StandardUsernamePasswordCredentials.class);
      return result;
    }

    @POST
    public ListBoxModel doFillRegionItems() {

      getJenkins().checkPermission(Jenkins.ADMINISTER);

      final StandardListBoxModel options = new StandardListBoxModel();

      options.includeEmptyValue();

      // NO AWS API Calls here
      for (Region r : RegionUtils.getRegionsForService(AWSCodeBuild.ENDPOINT_PREFIX)) {
        options.add(r.getName());
      }
      return options;
    }

    @POST
    public FormValidation doCheckRegion(@QueryParameter String value) {
      return checkValue(value, "Must include a region");
    }

    @POST
    public ListBoxModel doFillCodeBuildProjectNameItems(@QueryParameter String credentialId,
        @QueryParameter String region) {

      getJenkins().checkPermission(Jenkins.ADMINISTER);

      final StandardListBoxModel options = new StandardListBoxModel();
      options.includeEmptyValue();

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
          LOGGER.log(Level.SEVERE, "Unhandled AWS Exception listing CodeBuild Projects: ", e);
          return options;
        }
      } catch (Exception e) {
        LOGGER.log(Level.SEVERE, "Unhandled General Exception listing CodeBuild Projects: ", e);
        return options;

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
    public FormValidation doCheckCodeBuildProjectName(@QueryParameter String value) {
      return checkValue(value, "Invalid CodeBuild project selected");
    }

    @POST
    public FormValidation doCheckBuildSpec(@QueryParameter String value) {
      getJenkins().checkPermission(Jenkins.ADMINISTER);

      // Validate its correct YAML at least
      try {
        new Yaml(new SafeConstructor(new LoaderOptions())).load(value);
        return FormValidation.ok();
      } catch (Exception e) {
        return FormValidation.error("Incorrect YAML DEFINITION: " + e.toString());
      }
    }

    @POST
    public ListBoxModel doFillEnvironmentTypeItems() {
      final StandardListBoxModel options = new StandardListBoxModel();
      options.includeEmptyValue();

      // From here:
      // https://docs.aws.amazon.com/en_us/AWSJavaSDK/latest/javadoc/com/amazonaws/services/codebuild/model/EnvironmentType.html
      // NO AWS API Calls here

      List<String> envTypes = new ArrayList<String>();
      for (EnvironmentType theValue : com.amazonaws.services.codebuild.model.EnvironmentType.values()) {
        envTypes.add(theValue.name());
      }

      Collections.sort(envTypes);

      for (String envtype : envTypes) {
        options.add(envtype);
      }

      return options;
    }

    @POST
    public FormValidation doCheckEnvironmentType(@QueryParameter String value) {
      return checkValue(value, "Must include an EnvironmentType");
    }

    @POST
    public ListBoxModel doFillComputeTypeItems() {
      final StandardListBoxModel options = new StandardListBoxModel();
      options.includeEmptyValue();
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

    @POST
    public FormValidation doCheckComputeType(@QueryParameter String value) {
      return checkValue(value, "Must include a Compute Type");
    }

    @POST
    public FormValidation doCheckAgentConnectTimeout(@QueryParameter String value) {
      // Realistically an agent connection needs to be above 60 seconds
      return checkValue(value, 120, Integer.MAX_VALUE, "Invalid Agent Timeout Specified. ");
    }

    // Special naming convention that makes jelly work get****
    @POST
    public Integer getDefaultAgentConnectTimeout() {
      return DEFAULT_AGENT_CONNECT_TIMEOUT;
    }

    // Special naming convention that makes jelly work get****
    @POST
    public Integer getDefaultMaxAgents() {
      return DEFAULT_MAX_AGENTS;
    }

    @POST
    public FormValidation doCheckMaxAgents(@QueryParameter String value) {
      // Realistically an agent connection needs to be above 60 seconds
      return checkValue(value, 1, Integer.MAX_VALUE, "Invalid Max Agent Specified. ");
    }

    @POST
    public String getDefaultUrl() {
      JenkinsLocationConfiguration config = JenkinsLocationConfiguration.get();
      return StringUtils.defaultIfBlank(config.getUrl(), "unknown");
    }

    @POST
    public FormValidation doCheckUrl(@QueryParameter String value) {
      if (value.length() > 0) {
        try {
          new URL(value);
        } catch (MalformedURLException e) {
          return FormValidation.error("Invalid Jenkins URL: Exception: " + e.toString());
        }
      }
      return FormValidation.ok();
    }

    @POST
    public String getDefaultProtocols() {
      return DEFAULT_PROTOCOLS;
    }

    @POST
    public Boolean getDefaultNoReconnect() {
      return DEFAULT_NORECONNECT;
    }

    @POST
    public String getDefaultRegion() {
      try {
        return new DefaultAwsRegionProviderChain().getRegion();
      } catch (SdkClientException exc) {
        return null;
      }
    }

    @Override
    public String getDisplayName() {
      return Messages.CodeBuildCloud_DescriptorImpl_DisplayName();
    }

  }

}
