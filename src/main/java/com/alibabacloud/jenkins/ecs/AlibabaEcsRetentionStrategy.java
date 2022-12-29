package com.alibabacloud.jenkins.ecs;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.alibabacloud.jenkins.ecs.util.MinimumInstanceChecker;
import hudson.init.InitMilestone;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Job;
import hudson.model.Project;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution.PlaceholderTask;
import org.kohsuke.stapler.DataBoundConstructor;

public class AlibabaEcsRetentionStrategy extends RetentionStrategy<AlibabaEcsComputer> implements ExecutorListener {
    private static final Logger LOGGER = Logger.getLogger(AlibabaEcsRetentionStrategy.class.getName());
    public static final boolean DISABLED = Boolean.getBoolean(
        AlibabaEcsRetentionStrategy.class.getName() + ".disabled");
    private long nextCheckAfter = -1;
    private transient Clock clock;
    private final int idleTerminationMinutes;
    private transient ReentrantLock checkLock;
    private static final int STARTUP_TIME_DEFAULT_VALUE = 30;

    @DataBoundConstructor
    public AlibabaEcsRetentionStrategy(String idleTerminationMinutes) {
        readResolve();
        if (idleTerminationMinutes == null || idleTerminationMinutes.trim().isEmpty()) {
            this.idleTerminationMinutes = 0;
        } else {
            int value = STARTUP_TIME_DEFAULT_VALUE;
            try {
                value = Integer.parseInt(idleTerminationMinutes);
            } catch (NumberFormatException nfe) {
                LOGGER.info("Malformed default idleTermination value: " + idleTerminationMinutes);
            }

            this.idleTerminationMinutes = value;
        }
    }

    protected Object readResolve() {
        checkLock = new ReentrantLock(false);
        clock = Clock.systemUTC();
        return this;
    }

    /**
     * Called when a new {@link AlibabaEcsComputer} object is introduced (such as when Hudson started, or when
     * a new agent is added.)
     * <p>
     * When Jenkins has just started, we don't want to spin up all the instances, so we only start if
     * the ECS instance is already running
     */
    @Override
    public void start(AlibabaEcsComputer c) {
        //Jenkins is in the process of starting up
        if (Jenkins.get().getInitLevel() != InitMilestone.COMPLETED) {
            String state = null;
            try {
                state = c.status();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Error getting ECS instance state for " + c.getName(), e);
            }
            if (!c.isAlive()) {
                LOGGER.info("Ignoring start request for " + c.getName()
                        + " during Jenkins startup due to ECS instance state of " + state);
                return;
            }
        }
        LOGGER.info("Start requested for " + c.getName());
        c.connect(false);
    }

    // no registration since this retention strategy is used only for ECS nodes
    // that we provision automatically.
    // @Extension
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "Alibaba Cloud ECS";
        }
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        AlibabaEcsComputer computer = (AlibabaEcsComputer)executor.getOwner();
        if (computer == null || computer.getNode() == null) {
            LOGGER.warning("taskAccepted but computer is null or node is null.");
            return;
        }
        int maxTotalUses = 0;
        String instanceId = "";
        String currentBuildName = tryGetBuildName(task);
        AlibabaEcsSpotFollower node = computer.getNode();
        if (null != node) {
            maxTotalUses = node.maxTotalUses;
            instanceId = node.getEcsInstanceId();
        }
        if (maxTotalUses <= 0) {
            // 不限制单个节点的复用次数
            LOGGER.fine("maxTotalUses set to unlimited (" + node.maxTotalUses + ") for agent " + instanceId
                + " taskDisplayName: " + task.getDisplayName()
                + " currentBuildName: " + currentBuildName);

        } else if (maxTotalUses == 1) {
            // 单个节点只能使用一次, 用完就不再接受新的任务, 销毁;
            // 此时可以将分配在该节点的buildName设置为instanceName, 方便用户账单统计
            LOGGER.info("maxTotalUses drained - suspending agent " + instanceId
                + " taskDisplayName: " + task.getDisplayName()
                + " currentBuildName: " + currentBuildName);
            computer.setAcceptingTasks(false);
            String instanceName = node.instanceNamePrefix + currentBuildName;
            computer.modifyInstanceName(instanceName);
        } else {
            // 单个节点只能使用N次, 每次使用完, 都递减1, 直到为1, 从而进入到 "maxTotalUses == 1" 分支
            if (null != node) {
                node.maxTotalUses = node.maxTotalUses - 1;
                LOGGER.info("Agent " + node.getEcsInstanceId() + " has " + node.maxTotalUses + " builds left"
                    + " taskDisplayName: " + task.getDisplayName()
                    + " currentBuildName: " + currentBuildName);
            }
        }

    }

    private String tryGetBuildName(Queue.Task task) {
        String currentBuildName = "";
        try {
            // 这里去尝试获取Job构建序号的名称, 例如 jenkins_test#1; 没有的话, 则fallback到使用Job名称
            if (task instanceof Job) {
                currentBuildName = ((Job)task).getLastBuild().toString();
                if (StringUtils.isBlank(currentBuildName)) {
                    currentBuildName = "";
                }
                LOGGER.info("taskAccepted current taskType: Job currentBuildName: " + currentBuildName);
            } else if (task instanceof Project) {
                AbstractBuild lastBuild = ((Project)task).getLastBuild();
                if (null != lastBuild) {
                    currentBuildName = lastBuild.toString();
                    if (StringUtils.isBlank(currentBuildName)) {
                        currentBuildName = "";
                    }
                }
                LOGGER.info("taskAccepted current taskType: Project currentBuildName: " + currentBuildName);
            } else if (task instanceof PlaceholderTask) {
                Run<?, ?> run = ((PlaceholderTask)task).run();
                if (null != run) {
                    currentBuildName = run.getExternalizableId();
                    if (StringUtils.isBlank(currentBuildName)) {
                        currentBuildName = "";
                    }
                }
                currentBuildName = StringUtils.replaceChars(currentBuildName, '/', '-');
                LOGGER.info("taskAccepted current taskType: PlaceholderTask currentBuildName: " + currentBuildName);
            } else {
                String simpleName = task.getClass().getSimpleName();
                // task.getName() 获取到的是整个Job的名称,  例如 jenkins_test
                currentBuildName = task.getName();
                LOGGER.warning("taskAccepted current taskType: " + simpleName + " use job name as currentBuildName: "
                    + currentBuildName);
            }
            if (StringUtils.isBlank(currentBuildName)) {
                currentBuildName = task.getName();
            }
        } catch (Exception e) {
            String simpleName = task.getClass().getSimpleName();
            if (StringUtils.isNotBlank(task.getName())) {
                LOGGER.warning("taskAccepted current taskType: " + simpleName + " use job name as build name: "
                    + currentBuildName);
            } else {
                LOGGER.warning("taskAccepted current taskType: " + simpleName + " use job name as build name is null ");
            }
        }

        // 由于要使用 JobName#BuildId 作为ECS的实例名称, 但ECS实例名称格式有限制, 不能使用'#', 空格等特殊字符
        currentBuildName = StringUtils.replaceChars(currentBuildName, '#', '-');
        currentBuildName = StringUtils.remove(currentBuildName, ' ');
        return currentBuildName;
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long l) {
        postJobAction(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long l, Throwable throwable) {
        postJobAction(executor);
    }

    private void postJobAction(Executor executor) {
        AlibabaEcsComputer computer = (AlibabaEcsComputer) executor.getOwner();
        AlibabaEcsSpotFollower slaveNode = computer.getNode();
        if (slaveNode != null) {
            // At this point, if agent is in suspended state and has 1 last executer running, it is safe to terminate.
            if (computer.countBusy() <= 1 && !computer.isAcceptingTasks()) {
                LOGGER.info("Agent " + slaveNode.getEcsInstanceId() + " is terminated due to maxTotalUses ");
                slaveNode.terminate();
            }
        }
    }

    @Override
    public long check(AlibabaEcsComputer ecsComputer) {
        if (!checkLock.tryLock()) {
            return 1;
        } else {
            try {
                long currentTime = this.clock.millis();
                if (currentTime > nextCheckAfter) {
                    long intervalMinutes = internalCheck(ecsComputer);
                    nextCheckAfter = currentTime + TimeUnit.MINUTES.toMillis(intervalMinutes);
                    return intervalMinutes;
                } else {
                    return 1;
                }
            } finally {
                checkLock.unlock();
            }
        }
    }

    private long internalCheck(AlibabaEcsComputer computer) {
        // If we've been told never to terminate, or node is null(deleted), no checks to perform
        if (idleTerminationMinutes == 0 || computer.getNode() == null) {
            return 1;
        }

        //If we have equal or less number of slaves than the template's minimum instance count, don't perform check.
        AlibabaEcsFollowerTemplate slaveTemplate = computer.getSlaveTemplate();
        if (slaveTemplate != null) {
            long numberOfCurrentInstancesForTemplate = MinimumInstanceChecker.countCurrentNumberOfAgents(slaveTemplate);
            if (numberOfCurrentInstancesForTemplate > 0 && numberOfCurrentInstancesForTemplate <= slaveTemplate.getMinimumNumberOfInstances()) {
                return 1;
            }
        }

        long uptime;
        String state;
        try {
            state = computer.status();
            uptime = computer.getUptime();
        } catch (Exception e) {
            LOGGER.fine("Exception while checking host uptime for " + computer.getName()
                    + ", will retry next check. Exception: " + e);
            return 1;
        }

        if (computer.isOffline()) {
            if (computer.isConnecting()) {
                LOGGER.log(Level.INFO, "Computer {0} connecting and still offline, will check if the launch timeout has expired", computer.getInstanceId());

                AlibabaEcsSpotFollower node = computer.getNode();
                if (Objects.isNull(node)) {
                    return 1;
                }
                long launchTimeout = node.getLaunchTimeoutInMillis();
                if (launchTimeout > 0 && uptime > launchTimeout) {
                    // Computer is offline and startup time has expired
                    LOGGER.info("Startup timeout of " + computer.getName() + " after "
                            + uptime +
                            " milliseconds (timeout: " + launchTimeout + " milliseconds), instance status: " + state.toString());
                    node.launchTimeout();
                }
                return 1;
            } else {
                LOGGER.log(Level.INFO, "Computer {0} offline but not connecting, will check if it should be terminated because of the idle time configured", computer.getInstanceId());
            }
        }

        if (computer.isIdle() && !DISABLED) {
            if ("Stopped".equals(state) || "Stopping".equals(state)) {
                if (computer.isOnline()) {
                    computer.disconnect(null);
                }
                return 1;
            }

            final long idleMilliseconds = this.clock.millis() - computer.getIdleStartMilliseconds();
            if (idleTerminationMinutes <= 0) {
                return 1;
            }
            if (idleMilliseconds > TimeUnit.MINUTES.toMillis(idleTerminationMinutes)) {
                LOGGER.info("Idle timeout of " + computer.getName() + " after "
                        + TimeUnit.MILLISECONDS.toMinutes(idleMilliseconds) +
                        " idle minutes, instance status: " + state);
                AlibabaEcsSpotFollower slaveNode = computer.getNode();
                if (slaveNode != null) {
                    slaveNode.idleTimeout();
                }
            }
        }

        return 1;
    }
}
