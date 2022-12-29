package com.alibabacloud.jenkins.ecs;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

import javax.annotation.Nonnull;

import com.google.common.collect.Lists;
import hudson.model.Descriptor.FormException;
import hudson.model.TaskListener;
import hudson.slaves.SlaveComputer;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Created by kunlun.ykl on 2020/8/25.
 */
@Ignore
public class AlibabaEcsUnixComputerLauncherTest {
    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void launchTest() throws IOException, FormException {
        AlibabaEcsUnixComputerLauncher launcher = new AlibabaEcsUnixComputerLauncher();
        String ecsInstanceId = "i-abc";
        String name = "hanting-test";
        String remoteFS = "/root";
        String cloudName = "alibaba-ecs-cloud";
        String labelString = "myCI";
        String initScript = "";
        String templateName = "alibaba-ecs-cloud-t1";
        String userData = "userData-test";
        int numExecutors = 1;
        int launchTimeout = 10000;
        List<AlibabaEcsTag> tags = Lists.newArrayList();
        String idleTerminationMinutes = "30";
        UnixData unixData = new UnixData();
        AlibabaEcsSpotFollower follower = new AlibabaEcsSpotFollower(ecsInstanceId, name, remoteFS, cloudName,
            labelString, initScript, templateName, numExecutors, launchTimeout, tags, idleTerminationMinutes, userData,
            unixData, "", 1, "");
        r.jenkins.addNode(follower);

        SlaveComputer slaveComputer = new SlaveComputer(follower);
        TaskListener listener = new TaskListener() {
            @Nonnull
            @Override
            public PrintStream getLogger() {
                return System.out;
            }
        };
        launcher.launch(slaveComputer, listener);
    }
}
