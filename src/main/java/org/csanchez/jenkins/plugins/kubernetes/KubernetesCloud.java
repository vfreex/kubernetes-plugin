package org.csanchez.jenkins.plugins.kubernetes;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.nirima.jenkins.plugins.docker.DockerTemplate;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import io.fabric8.kubernetes.api.Kubernetes;
import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kubernetes cloud provider.
 * 
 * Starts slaves in a Kubernetes cluster using defined Docker templates for each label.
 * 
 * @author Carlos Sanchez carlos@apache.org
 */
public class KubernetesCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(KubernetesCloud.class.getName());
    private static final Pattern SPLIT_IN_SPACES = Pattern.compile("([^\"]\\S*|\".+?\")\\s*");

    public static final String CLOUD_ID_PREFIX = "kubernetes-";

    private static final String DEFAULT_ID = "jenkins-slave-default";

    /** label for all pods started by the plugin */
    private static final Map<String, String> POD_LABEL = ImmutableMap.of("jenkins", "slave");

    private static final String CONTAINER_NAME = "slave";

    public final List<DockerTemplate> templates;
    public final String serverUrl;
    public final String serverCertificate;
    public final String jenkinsUrl;
    public final String jenkinsTunnel;
    public final String username;
    public final String password;
    public final int containerCap;

    private transient Kubernetes connection;

    @DataBoundConstructor
    public KubernetesCloud(String name, List<? extends DockerTemplate> templates,
                           String serverUrl, String serverCertificate,
                           String jenkinsUrl,String jenkinsTunnel,
                           String username, String password, String containerCapStr, int connectTimeout, int readTimeout) {
        super(name);

        Preconditions.checkNotNull(serverUrl);

        this.serverUrl = serverUrl;
        this.serverCertificate = serverCertificate;
        this.jenkinsUrl = jenkinsUrl;
        this.jenkinsTunnel = jenkinsTunnel;
        this.username = username;
        this.password = password;
        if (templates != null)
            this.templates = new ArrayList<DockerTemplate>(templates);
        else
            this.templates = new ArrayList<DockerTemplate>();

        if (containerCapStr.equals("")) {
            this.containerCap = Integer.MAX_VALUE;
        } else {
            this.containerCap = Integer.parseInt(containerCapStr);
        }
    }

    public String getContainerCapStr() {
        if (containerCap == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(containerCap);
        }
    }

    /**
     * Connects to Docker.
     *
     * @return Docker client.
     */
    public Kubernetes connect() {

        LOGGER.log(Level.FINE, "Building connection to Kubernetes host " + name + " URL " + serverUrl);

        if (connection == null) {
            synchronized (this) {
                if (connection != null)
                    return connection;

                connection = new KubernetesFactoryAdapter(serverUrl, serverCertificate, username, password)
                        .createKubernetes();
            }
        }
        return connection;

    }

    private String getIdForLabel(Label label) {
        if (label == null) {
            return DEFAULT_ID;
        }
        return "jenkins-" + label.getName();
    }

    private Pod getPodTemplate(KubernetesSlave slave, Label label) {
        DockerTemplate template = getTemplate(label);
        String id = getIdForLabel(label);
        Pod pod = new Pod();

        KubernetesHelper.setName(pod, slave.getNodeName());

        // labels
        pod.getMetadata().setLabels(getLabelsFor(id));

        Container manifestContainer = new Container();
        manifestContainer.setName(CONTAINER_NAME);
        manifestContainer.setImage(template.getImage());

        // environment
        // List<EnvVar> env = new
        // ArrayList<EnvVar>(template.environment.length + 3);
        List<EnvVar> env = new ArrayList<EnvVar>(3);
        // always add some env vars
        env.add(new EnvVar("JENKINS_SECRET", slave.getComputer().getJnlpMac(), null));
        env.add(new EnvVar("JENKINS_LOCATION_URL", JenkinsLocationConfiguration.get().getUrl(), null));
        if (!StringUtils.isBlank(jenkinsUrl)) {
            env.add(new EnvVar("JENKINS_URL", jenkinsUrl, null));
        }
        if (!StringUtils.isBlank(jenkinsTunnel)) {
            env.add(new EnvVar("JENKINS_TUNNEL", jenkinsTunnel, null));
        }
        String url = StringUtils.isBlank(jenkinsUrl) ? JenkinsLocationConfiguration.get().getUrl() : jenkinsUrl;
        url = url.endsWith("/") ? url : url + "/";
        env.add(new EnvVar("JENKINS_JNLP_URL", url + slave.getComputer().getUrl() + "slave-agent.jnlp", null));
        // for (int i = 0; i < template.environment.length; i++) {
        // String[] split = template.environment[i].split("=");
        // env.add(new EnvVar(split[0], split[1]));
        // }
        manifestContainer.setEnv(env);

        // ports
        // TODO open ports defined in template
        // container.setPorts(new Port(22, RAND.nextInt((65535 - 49152) + 1) +
        // 49152));

        // command: SECRET SLAVE_NAME
        List<String> cmd = parseDockerCommand(template.dockerCommand);
        cmd = cmd == null ? new ArrayList<String>(2) : cmd;
        cmd.add(slave.getComputer().getJnlpMac()); // secret
        cmd.add(slave.getComputer().getName()); // name
        manifestContainer.setCommand(cmd);

        List<Container> containers = new ArrayList<Container>();
        containers.add(manifestContainer);

        PodSpec podSpec = new PodSpec();
        pod.setSpec(podSpec);
        podSpec.setContainers(containers);

        return pod;
    }

    private Map<String, String> getLabelsFor(String id) {
        return ImmutableMap.<String, String> builder().putAll(POD_LABEL).putAll(ImmutableMap.of("name", id)).build();
    }

    /**
     * Split a command in the parts that Docker need
     * 
     * @param dockerCommand
     * @return
     */
    List<String> parseDockerCommand(String dockerCommand) {
        if (dockerCommand == null || dockerCommand.isEmpty()) {
            return null;
        }
        // handle quoted arguments
        Matcher m = SPLIT_IN_SPACES.matcher(dockerCommand);
        List<String> commands = new ArrayList<String>();
        while (m.find()) {
            commands.add(m.group(1).replace("\"", ""));
        }
        return commands;
    }

    @Override
    public synchronized Collection<NodeProvisioner.PlannedNode> provision(final Label label, final int excessWorkload) {
        try {

            LOGGER.log(Level.INFO, "Excess workload after pending Spot instances: " + excessWorkload);

            List<NodeProvisioner.PlannedNode> r = new ArrayList<NodeProvisioner.PlannedNode>();

            final DockerTemplate t = getTemplate(label);

            for (int i = 1; i <= excessWorkload; i++) {
                if (!addProvisionedSlave(t, label)) {
                    break;
                }

                r.add(new NodeProvisioner.PlannedNode(t.getDisplayName(), Computer.threadPoolForRemoting
                        .submit(new ProvisioningCallback(this, t, label)), t.getNumExecutors()));
            }
            return r;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to count the # of live instances on Kubernetes", e);
            return Collections.emptyList();
        }
    }

    private class ProvisioningCallback implements Callable<Node> {
        private final KubernetesCloud cloud;
        private final DockerTemplate t;
        private final Label label;

        public ProvisioningCallback(KubernetesCloud cloud, DockerTemplate t, Label label) {
            this.cloud = cloud;
            this.t = t;
            this.label = label;
        }

        public Node call() throws Exception {
            KubernetesSlave slave = null;
            try {

                slave = new KubernetesSlave(t, getIdForLabel(label), cloud, label);
                Jenkins.getInstance().addNode(slave);

                Pod pod = getPodTemplate(slave, label);
                // Why the hell doesn't createPod return a Pod object ?
                String podJson = connect().createPod(pod, "jenkins-slave");

                String podId = pod.getMetadata().getName();
                LOGGER.log(Level.INFO, "Created Pod: {0}", podId);

                // We need the pod to be running and connected before returning
                // otherwise this method keeps being called multiple times
                ImmutableList<String> validStates = ImmutableList.of("Running");

                int i = 0;
                int j = 600; // wait 600 seconds

                // wait for Pod to be running
                for (; i < j; i++) {
                    LOGGER.log(Level.INFO, "Waiting for Pod to be scheduled ({1}/{2}): {0}", new Object[] {podId, i, j});
                    Thread.sleep(1000);
                    pod = connect().getPod(podId, "jenkins-slave");
                    if (pod == null) {
                        throw new IllegalStateException("Pod no longer exists: " + podId);
                    }
                    ContainerStatus info = getContainerStatus(pod, CONTAINER_NAME);
                    if (info != null) {
                        if (info.getState().getWaiting() != null) {
                            // Pod is waiting for some reason
                            LOGGER.log(Level.INFO, "Pod is waiting {0}: {1}",
                                    new Object[] { podId, info.getState().getWaiting() });
                            // break;
                        }
                        if (info.getState().getTermination() != null) {
                            throw new IllegalStateException("Pod is terminated. Exit code: "
                                    + info.getState().getTermination().getExitCode());
                        }
                    }
                    if (validStates.contains(pod.getStatus().getPhase())) {
                        break;
                    }
                }
                String status = pod.getStatus().getPhase();
                if (!validStates.contains(status)) {
                    throw new IllegalStateException("Container is not running after " + j + " attempts: " + status);
                }

                // now wait for slave to be online
                for (; i < j; i++) {
                    if (slave.getComputer().isOnline()) {
                        break;
                    }
                    LOGGER.log(Level.INFO, "Waiting for slave to connect ({1}/{2}): {0}", new Object[] { podId,
                            i, j });
                    Thread.sleep(1000);
                }
                if (!slave.getComputer().isOnline()) {
                    throw new IllegalStateException("Slave is not connected after " + j + " attempts: " + status);
                }

                return slave;
            } catch (Throwable ex) {
                LOGGER.log(Level.SEVERE, "Error in provisioning; slave={0}, template={1}", new Object[] { slave, t });
                ex.printStackTrace();
                throw Throwables.propagate(ex);
            }
        }
    }

    private ContainerStatus getContainerStatus(Pod pod, String containerName) {

        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            if (status.getName().equals(containerName)) return status;
        }
        return null;
    }

    /**
     * Check not too many already running.
     *
     */
    private boolean addProvisionedSlave(DockerTemplate template, Label label) throws Exception {
        if (containerCap == 0) {
            return true;
        }

        PodList allPods = connect().getPods("jenkins-slave");

        if (allPods.getItems().size() >= containerCap) {
            LOGGER.log(Level.INFO, "Total container cap of " + containerCap + " reached, not provisioning.");
            return false; // maxed out
        }

        /*
        PodList labelPods = connect().getSelectedPods(ImmutableMap.of("name", getIdForLabel(label)));

        if (labelPods.getItems().size() >= template.instanceCap) {
            LOGGER.log(Level.INFO, "Template instance cap of " + template.instanceCap + " reached for template "
                    + template.getImage() + ", not provisioning.");
            return false; // maxed out
        }
        */

        return true;
    }

    @Override
    public boolean canProvision(Label label) {
        return getTemplate(label) != null;
    }

    public DockerTemplate getTemplate(String template) {
        for (DockerTemplate t : templates) {
            if (t.getImage().equals(template)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Gets {@link DockerTemplate} that has the matching {@link Label}.
     * @param label label to look for in templates
     * @return the template
     */
    public DockerTemplate getTemplate(Label label) {
        for (DockerTemplate t : templates) {
            if (label == null || label.matches(t.getLabelSet())) {
                return t;
            }
        }
        return null;
    }

    /**
     * Add a new template to the cloud
     * @param t docker template
     */
    public void addTemplate(DockerTemplate t) {
        this.templates.add(t);
        // t.parent = this;
    }

    /**
     * Remove a
     * 
     * @param t docker template
     */
    public void removeTemplate(DockerTemplate t) {
        this.templates.remove(t);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Kubernetes";
        }

        public FormValidation doTestConnection(@QueryParameter URL serverUrl, @QueryParameter String username,
                @QueryParameter String password, @QueryParameter String serverCertificate) throws URISyntaxException {

            Kubernetes kube = new KubernetesFactoryAdapter(serverUrl.toExternalForm(), serverCertificate, username, password)
                    .createKubernetes();
            kube.getNodes();

            return FormValidation.ok("Connection successful");
        }
    }

    @Override
    public String toString() {
        return "KubernetesCloud '" + name + "' serverUrl :" + serverUrl;
    }
}
