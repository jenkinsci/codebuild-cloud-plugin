package io.jenkins.plugins.codebuildcloud;

import java.io.IOException;
import java.util.Collections;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;

import com.amazonaws.services.codebuild.model.ResourceNotFoundException;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;

public class CodeBuildAgent extends AbstractCloudSlave {

  final transient CodeBuildCloud cloud;
  private static final Logger LOGGER = Logger.getLogger(CodeBuildAgent.class.getName());
  private static final long serialVersionUID = 1; // SpotBugs

  transient boolean terminated = false;

  public CodeBuildAgent(String name, @NonNull CodeBuildCloud cloud, @NonNull ComputerLauncher launcher)
      throws Descriptor.FormException, IOException {
    super(name,
        "/build",
        launcher);

    this.setNodeDescription("CodeBuild Agent");
    this.setNumExecutors(1);
    this.setMode(Mode.EXCLUSIVE);
    this.setLabelString(cloud.getLabel());

    // Somewhat arbitrary
    // We only care about this check if the worker is idle. If it is still launching
    // we have disabled the OnceRetention policy. As such I think we can hard code
    // this because the only way the RetentionPolicy terminates with the check - is
    // if we missed one of the taskcompleted events.
    // More of an edge condition than anything - which is why we wrote our own
    this.setRetentionStrategy(new CodeBuildRetentionStrategy());

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
    LOGGER.finest("Terminating agent: " + getDisplayName());

    if (getLauncher() instanceof CodeBuildLauncher) {
      CodeBuildComputer comp = (CodeBuildComputer) getComputer();
      if (comp == null) {
        terminated = true;
        return;
      }

      String buildId = comp.getBuildId();
      if (StringUtils.isBlank(buildId)) {
        terminated = true;
        return;
      }

      LOGGER.finest("Terminating agent Step2: " + getDisplayName());
      try {
        if (comp.getCompletedWithoutErrors()) {
          // Let the rest of the build process run within codebuild
          // Do not call stop - let codebuild nateively end.
          // See #https://github.com/jenkinsci/codebuild-cloud-plugin/issues/21
        } else {
          // Stop hard the build in codebuild. Saves jenkins admin money
          cloud.getClient().stopBuild(buildId);
        }
      } catch (ResourceNotFoundException e) {
        // this is fine. really.
      } catch (Exception e) {
        LOGGER.severe(String.format("Failed to stop build ID: %s.  Exception: %s", buildId, e));
      }
      terminated = true;
    }
  }
}
