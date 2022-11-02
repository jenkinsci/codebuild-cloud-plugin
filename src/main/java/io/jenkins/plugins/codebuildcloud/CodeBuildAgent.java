package io.jenkins.plugins.codebuildcloud;

import java.io.IOException;
import java.util.Collections;
import java.util.logging.Logger;



import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;

import com.amazonaws.services.codebuild.model.ResourceNotFoundException;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;

public class CodeBuildAgent extends AbstractCloudSlave {

  private final transient CodeBuildCloud cloud;
  private static final Logger LOGGER = Logger.getLogger(CodeBuildAgent.class.getName());
  private static final long serialVersionUID = 1; // SpotBugs

  public CodeBuildAgent(String name, @NonNull CodeBuildCloud cloud, @NonNull ComputerLauncher launcher)
      throws Descriptor.FormException, IOException {
    super(name,
        "/build",
        launcher);

    this.setNodeDescription("CodeBuild Agent");
    this.setNumExecutors(1);
    this.setMode(Mode.EXCLUSIVE);
    this.setLabelString(cloud.getLabel());
    this.setRetentionStrategy(new OnceRetentionStrategy(cloud.getAgentTimeout() / 60 + 1));
    this.setNodeProperties(Collections.emptyList());
    this.cloud = cloud;

  }

  @Override
  public AbstractCloudComputer<CodeBuildAgent> createComputer() {
    return new CodeBuildComputer(this);
  }

  /** {@inheritDoc} */
  @Override
  protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
    listener.getLogger().println("Terminating agent: " + getDisplayName());

    if (getLauncher() instanceof CodeBuildLauncher) {
      CodeBuildComputer comp = (CodeBuildComputer) getComputer();
      if (comp == null) {
        return;
      }

      String buildId = comp.getBuildId();
      if (StringUtils.isBlank(buildId)) {
        return;
      }

      try {
        cloud.getClient().stopBuild(buildId);
      } catch (ResourceNotFoundException e) {
        // this is fine. really.
      } catch (Exception e) {
        LOGGER.severe(String.format("Failed to stop build ID: %s.  Exception: %s", buildId, e));
      }
    }
  }
}
