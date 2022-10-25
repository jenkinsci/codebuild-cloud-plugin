package io.jenkins.plugins.codebuildcloud;

import java.io.IOException;
import java.util.Collections;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import org.apache.commons.lang.StringUtils;

import com.amazonaws.services.codebuild.model.ResourceNotFoundException;

import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.ComputerLauncher;

public class CodeBuildSlave extends AbstractCloudSlave {

  private final transient CodeBuildCloud cloud;
  private static final Logger LOGGER = Logger.getLogger(CodeBuildSlave.class.getName());
  private static final long serialVersionUID = 1; // SpotBugs 

  public CodeBuildSlave(String name, CodeBuildCloud cloud,  @Nonnull ComputerLauncher launcher ) throws Descriptor.FormException, IOException{
    super(name, 
         "/build", 
         launcher);

    this.setNodeDescription("CodeBuild Agent");
    this.setNumExecutors(1);
    this.setMode(Mode.EXCLUSIVE);
    this.setLabelString(cloud.getLabel());
    this.setRetentionStrategy(new CloudRetentionStrategy(cloud.getAgentTimeout()/60 + 1));
    this.setNodeProperties(Collections.emptyList());
    this.cloud= cloud;

  }

  public CodeBuildCloud getCloud(){
    return cloud;

  }

  @Override
  public AbstractCloudComputer<CodeBuildSlave> createComputer() {
    return new CodeBuildComputer(this);
  }

    /** {@inheritDoc} */
    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
      listener.getLogger().println("Terminating agent: " + getDisplayName());

      if (getLauncher() instanceof CodeBuildLauncher) {
        CodeBuildComputer comp = (CodeBuildComputer) getComputer();
        if (comp == null){
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
          LOGGER.severe(String.format("Failed to stop build ID: %s.  Exception: %s", buildId,e));
        }
      }
    }
}
