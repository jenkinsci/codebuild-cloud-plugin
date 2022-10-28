# codebuild-cloud

## Introduction

Makes Codebuild buildjobs a Jenkins Agent.  
- Using something like the [ECS Plugin](https://github.com/jenkinsci/amazon-ecs-plugin/) with Fargate works great for Jenkins builds that are not creating docker images.
- Using the official [Codebuild Plugin](https://plugins.jenkins.io/aws-codebuild/) for Jenkins works great if you want your build being mostly defined in the `Buildspec.yml` file.  However what if you want to use the `Jenkinsfile` for pipeline code?
- This plugin is a derivative of the [CodeBuilder Plugin](https://github.com/jenkinsci/codebuilder-cloud-plugin) that [Loren Segal](https://github.com/lsegal) wrote.  I wanted the plugin to be able to use the off the shelf images coming out of codebuild for a few reasons:
  - The latest AWS official images are always cached in the CodeBuild environment.  Using your own custom image easily adds 3-5 minutes per build based on my experience with it.
  - If your primary build methodology is containers, you dont actually care much about the base image since most of the work is happening in the dockerfile. 
  - To this end eliminating the maintenence of the agent image and getting Jenkins Agents to connect was the primary goal, as well as enabling any custom logic via a custom `buildspec.yml` within the jenkins cloud interface.

## Getting started

### User Quick Start
- Install the plugin from the update center
- You need a CodeBuild build project.  An out of the box one will do.  Many of the parameters we are going to be overriding, see `CodeBuildLauncher` in the source if you want to know which ones.
  - Option 1 CLI:
    ```
    # Optional command to create a bare IAM role named "jenkins-default"
    aws iam create-role \
    --role-name jenkins-default \
      --assume-role-policy-document \
      '{"Version":"2012-10-17","Statement":[{"Effect": "Allow","Principal":{"Service":"codebuild.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

    # Create the project named "jenkins-build" using our service role
    aws codebuild create-project \
      --name jenkins-build \
      --service-role nick-default \
      --artifacts type=NO_ARTIFACTS \
      --environment type=LINUX_CONTAINER,image=aws/codebuild/docker:18.09.0,computeType=BUILD_GENERAL1_SMALL \
      --source $'type=NO_SOURCE, buildspec=version:0.2\nphases:\n  build:\n    commands:\n      - exit 1'
    ```
  - Option 2 CFT, with a use case if you require the build agent internally on a VPC.  Use the CFT from ` cft/example_cft.yml`
- Configure the plugin
  - Here we need to know a little bit more on how we plan on the image connecting to jenkins.  Will it go through a websocket, direct connection or tunnel via proxy?  I will explain a simple case, but each case will be different.
  - Add a new cloud via `Manage Jenkins --> Manage Nodes and Clouds --> Configure Clouds`
  - See `resources/images/param1.png` and `resources/images/param2.png` . If jenkins is running as a role with the correct permisisons, AWS Credentials can be ignored.  If not, an IAM user should be setup with needed permissions
    - Permissions needed by the jenkins master/controller:
      ```
      - Sid: CodeBuild 
        Action:
          - "codebuild:List*"
          - "codebuild:Describe*"
          - "codebuild:Get*"
          - "codebuild:StartBuild"
          - "codebuild:StopBuild"
          - "codebuild:BatchGet*"
        Resource:
          - "*"
      ```
  - Select the proper region.  The Codebuild Project Name parameter will dynamically load projects it finds, select the correct one.
  - label - whatever you want to label this agent type.  Something like `codebuild-agent`
  - Compute Type - Select the desired amount of resources
  - Environment Type and Docker Image -  Here you need to know what you are launching.  Is it an ARM container, or an X86/AMD64 one?  Is it a windows container?  Is it a custom container you have built out of ECR?  See help text to fill out these 2 parameters
  - Leave the defaults for `Disable reconnect` and `Agent Timeout` unless using ECR, real help.
  - Buildspec is where this plugin gets interesting.  Are you using a custom docker image or off the shelf?  If using an off the shelf image from AWS like `aws/codebuild/amazonlinux2-aarch64-standard:2.0	` it doesnt have jenkins agents deployed to it.  So we need to add those pieces.  If you are using your own custom image
    - Using off the shelf image, example below.  
      - Key takeaways: Do not use in production, figure out how to pull binaries from somewhere local in AWS like S3 or the jenkins master itself.  Also this assumes you want the docker daemon available to uou.
        ```
        version: 0.2

        phases:
          install:
            #If you use the Ubuntu standard image 2.0 or later, you must specify runtime-versions.
            #If you specify runtime-versions and use an image other than Ubuntu standard image 2.0, the build fails.
            #runtime-versions:
              # name: version
              # name: version
            commands:
              - curl --create-dirs -fsSLo /usr/share/jenkins/agent.jar https://repo.jenkins-ci.org/artifactory/releases/org/jenkins-ci/main/remoting/4.13.3/remoting-4.13.3.jar
              - chmod 755 /usr/share/jenkins && chmod 644 /usr/share/jenkins/agent.jar && ln -sf /usr/share/jenkins/agent.jar /usr/share/jenkins/slave.jar
              - mkdir -p /home/jenkins/.jenkins && mkdir -p /home/jenkins/agent
              -  curl --create-dirs  -fsSLo /usr/local/bin/jenkins-agent  https://raw.githubusercontent.com/carpnick/docker-inbound-agent/master/jenkins-agent
              - chmod +x /usr/local/bin/jenkins-agent && ln -s /usr/local/bin/jenkins-agent /usr/local/bin/jenkins-slave
          pre_build:
            commands:
              - which dockerd-entrypoint.sh >/dev/null && dockerd-entrypoint.sh || exit 0
              # - command
          build:
            commands:
              - /usr/local/bin/jenkins-agent $JENKINS_CODEBUILD_PROXY_CREDENTIALS $JENKINS_CODEBUILD_DISABLE_SSL_VALIDATION $JENKINS_CODEBUILD_NORECONNECT -workDir /home/jenkins/agent
        ```
    - Using JNLP image from Jenkins like [this](https://hub.docker.com/r/jenkins/inbound-agent/) one:

        ```
        version: 0.2

        phases:
          pre_build:
            commands:
              - which dockerd-entrypoint.sh >/dev/null && dockerd-entrypoint.sh || exit 0
          build:
            commands:
              - /usr/local/bin/jenkins-agent $JENKINS_CODEBUILD_PROXY_CREDENTIALS $JENKINS_CODEBUILD_DISABLE_SSL_VALIDATION $JENKINS_CODEBUILD_NORECONNECT -workDir /home/jenkins/agent
        ```
  - `Param3.png` shows the rest of the params you can set.  These are typical JNLP params for jenkins.  If you dont know how to use them, I would suggest looking [here](ttps://github.com/jenkinsci/remoting/blob/master/src/main/java/hudson/remoting/jnlp/Main.java)
- Write a pipeline to verify connectivity
  ```
    node("codebuild-agent"){
      echo "Hello World"
    }
  ```
- If you have issues, look at the jenkins log on the master/controller


### Developer Quick Start
  - Fork into your own namespace
  - Environment setup
    - Requires Java 11.  We use [Coretto](https://aws.amazon.com/corretto).
    - Export JAVA_HOME, IE:
      ```
      export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home
      ```
    - Make sure you have a new version of maven:
    `brew install maven`
    - `mvn verify` or `mvn clean install` to build
    - To run a local instance `mvn clean  hpi:run`

## Issues

Open issues here in Github

## Contributing

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)



## Helpful
- Jenkins.get().getComputer().get_all()[1].getJnlpMac()