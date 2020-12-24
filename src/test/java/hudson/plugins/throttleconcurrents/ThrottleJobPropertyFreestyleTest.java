/*
 * The MIT License
 * 
 * Copyright (c) 2016 IKEDA Yasuyuki
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.plugins.throttleconcurrents;

import static org.junit.Assert.assertEquals;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.EnvVars;
import hudson.model.FreeStyleProject;
import hudson.model.Node.Mode;
import hudson.plugins.throttleconcurrents.testutils.ExecutorWaterMarkRetentionStrategy;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import java.util.Collections;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;

/**
 * Tests that {@link ThrottleJobProperty} actually works for builds.
 */
public class ThrottleJobPropertyFreestyleTest {
    private final long SLEEP_TIME = 100;
    private int executorNum = 2;
    private ExecutorWaterMarkRetentionStrategy<SlaveComputer> waterMark;
    private DumbSlave agent = null;
    
    @Rule
    public JenkinsRule r = new JenkinsRule();

    /**
     * Copypasta of {@link JenkinsRule#createSlave(String, String, EnvVars)} to enable modifying the
     * number of executors.
     */
    private DumbSlave createSlave(String nodeName, String labels, EnvVars env) throws Exception {
        synchronized (r.jenkins) {
            DumbSlave agent = new DumbSlave(
                    nodeName,
                    "dummy",
                    r.createTmpDir().getPath(),
                    Integer.toString(executorNum),      // Overridden!
                    Mode.NORMAL,
                    labels==null?"":labels,
                    r.createComputerLauncher(env),
                    RetentionStrategy.NOOP,
                    Collections.emptyList()
            );
            r.jenkins.addNode(agent);
            return agent;
        }
    }
    
    /**
     * sets up agent and waterMark.
     */
    @Before
    public void setupAgent() throws Exception {
        int sz = r.jenkins.getNodes().size();
        agent = createSlave("agent" + sz, null, null);
        r.waitOnline(agent);
        waterMark = new ExecutorWaterMarkRetentionStrategy<SlaveComputer>(agent.getRetentionStrategy());
        agent.setRetentionStrategy(waterMark);
    }
    
    /**
     * setup security so that no one except SYSTEM has any permissions.
     * should be called after {@link #setupAgent()}
     */
    @Before
    public void setupSecurity() {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        GlobalMatrixAuthorizationStrategy auth = new GlobalMatrixAuthorizationStrategy();
        r.jenkins.setAuthorizationStrategy(auth);
    }
    
    @Test
    public void testNoThrottling() throws Exception {
        FreeStyleProject p1 = r.createFreeStyleProject();
        p1.setAssignedNode(agent);
        p1.getBuildersList().add(new SleepBuilder(SLEEP_TIME));
        
        FreeStyleProject p2 = r.createFreeStyleProject();
        p2.setAssignedNode(agent);
        p2.getBuildersList().add(new SleepBuilder(SLEEP_TIME));
        
        p1.scheduleBuild2(0);
        p2.scheduleBuild2(0);
        
        r.waitUntilNoActivity();
        
        // not throttled, and builds run concurrently.
        assertEquals(2, waterMark.getExecutorWaterMark());
    }
    
    @Test
    public void testThrottlingWithCategoryPerNode() throws Exception {
        final String category = "category";
        
        ThrottleJobProperty.DescriptorImpl descriptor
            = (ThrottleJobProperty.DescriptorImpl)r.jenkins.getDescriptor(ThrottleJobProperty.class);
        descriptor.setCategories(Collections.singletonList(
                new ThrottleJobProperty.ThrottleCategory(
                        category,
                        1,      // maxConcurrentPerNode
                        null,   // maxConcurrentTotal
                        Collections.emptyList()
                )
        ));
        
        FreeStyleProject p1 = r.createFreeStyleProject();
        p1.setAssignedNode(agent);
        p1.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(category),      // categories
                true,   // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY,     // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT
        ));
        p1.getBuildersList().add(new SleepBuilder(SLEEP_TIME));
        
        FreeStyleProject p2 = r.createFreeStyleProject();
        p2.setAssignedNode(agent);
        p2.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(category),      // categories
                true,   // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY,     // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT
        ));
        p2.getBuildersList().add(new SleepBuilder(SLEEP_TIME));
        
        p1.scheduleBuild2(0);
        p2.scheduleBuild2(0);
        
        r.waitUntilNoActivity();
        
        // throttled, and only one build runs at the same time.
        assertEquals(1, waterMark.getExecutorWaterMark());
    }
    
    @Test
    public void testThrottlingWithCategoryTotal() throws Exception {
        final String category = "category";

        ThrottleJobProperty.DescriptorImpl descriptor =
                (ThrottleJobProperty.DescriptorImpl)
                        r.jenkins.getDescriptor(ThrottleJobProperty.class);
        descriptor.setCategories(
                Collections.singletonList(
                        new ThrottleJobProperty.ThrottleCategory(
                                category,
                                null, // maxConcurrentPerNode
                                1, // maxConcurrentTotal
                                Collections.emptyList())));

        FreeStyleProject p1 = r.createFreeStyleProject();
        p1.setAssignedNode(agent);
        p1.addProperty(
                new ThrottleJobProperty(
                        null, // maxConcurrentPerNode
                        null, // maxConcurrentTotal
                        Collections.singletonList(category), // categories
                        true, // throttleEnabled
                        TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                        false,
                        null,
                        ThrottleMatrixProjectOptions.DEFAULT));
        p1.getBuildersList().add(new SleepBuilder(SLEEP_TIME));

        FreeStyleProject p2 = r.createFreeStyleProject();
        p2.setAssignedNode(agent);
        p2.addProperty(
                new ThrottleJobProperty(
                        null, // maxConcurrentPerNode
                        null, // maxConcurrentTotal
                        Collections.singletonList(category), // categories
                        true, // throttleEnabled
                        TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                        false,
                        null,
                        ThrottleMatrixProjectOptions.DEFAULT));
        p2.getBuildersList().add(new SleepBuilder(SLEEP_TIME));

        p1.scheduleBuild2(0);
        p2.scheduleBuild2(0);

        r.waitUntilNoActivity();

        // throttled, and only one build runs at the same time.
        assertEquals(1, waterMark.getExecutorWaterMark());
    }

    @Issue("JENKINS-25326")
    @Test
    public void testThrottlingWithCategoryInFolder() throws Exception {
        final String category = "category";
        
        ThrottleJobProperty.DescriptorImpl descriptor
            = (ThrottleJobProperty.DescriptorImpl)r.jenkins.getDescriptor(ThrottleJobProperty.class);
        descriptor.setCategories(Collections.singletonList(
                new ThrottleJobProperty.ThrottleCategory(
                        category,
                        1,      // maxConcurrentPerNode
                        null,   // maxConcurrentTotal
                        Collections.emptyList()
                )
        ));
        
        Folder f1 = r.createProject(Folder.class, "folder1");
        FreeStyleProject p1 = f1.createProject(FreeStyleProject.class, "p");
        p1.setAssignedNode(agent);
        p1.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(category),      // categories
                true,   // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY,     // throttleOption
                false,  // limitOneJobWithMatchingParams
                null,   // paramsToUse for the previous flag
                ThrottleMatrixProjectOptions.DEFAULT
        ));
        p1.getBuildersList().add(new SleepBuilder(SLEEP_TIME));
        
        Folder f2 = r.createProject(Folder.class, "folder2");
        FreeStyleProject p2 = f2.createProject(FreeStyleProject.class, "p");
        p2.setAssignedNode(agent);
        p2.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(category),      // categories
                true,   // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY,     // throttleOption
                false,  // limitOneJobWithMatchingParams
                null,   // paramsToUse for the previous flag
                ThrottleMatrixProjectOptions.DEFAULT
        ));
        p2.getBuildersList().add(new SleepBuilder(SLEEP_TIME));
        
        p1.scheduleBuild2(0);
        p2.scheduleBuild2(0);
        
        r.waitUntilNoActivity();
        
        // throttled, and only one build runs at the same time.
        assertEquals(1, waterMark.getExecutorWaterMark());
    }
}