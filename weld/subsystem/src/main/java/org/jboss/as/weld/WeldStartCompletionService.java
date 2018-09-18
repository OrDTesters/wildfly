/*
 * JBoss, Home of Professional Open Source
 * Copyright 2018, Red Hat Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.weld;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StabilityMonitor;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.weld.bootstrap.api.Bootstrap;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * This service calls {@link Bootstrap#endInitialization()}, i.e. places the container into a state where it can service
 * requests.
 *
 * Its start is delayed after all EE components are installed which allows Weld to perform efficient cleanup and further
 * optimizations after bootstrap.
 *
 * @author Martin Kouba
 * @author Matej Novotny
 * @see WeldStartService
 */
public class WeldStartCompletionService implements Service<WeldStartCompletionService> {

    public static final ServiceName SERVICE_NAME = ServiceNames.WELD_START_COMPLETION_SERVICE_NAME;

    private final InjectedValue<WeldBootstrapService> bootstrap = new InjectedValue<WeldBootstrapService>();
    private final InjectedValue<ExecutorService> executorService = new InjectedValue<ExecutorService>();

    private final ClassLoader classLoader;
    private final List<ServiceController> serviceControllers;

    private final AtomicBoolean runOnce = new AtomicBoolean();

    public WeldStartCompletionService(ClassLoader classLoader, List<ServiceController> serviceControllers) {
        this.classLoader = classLoader;
        this.serviceControllers = serviceControllers;
    }

    @Override
    public void start(final StartContext context) throws StartException {
        if (!runOnce.compareAndSet(false, true)) {
            // we only execute this once, if there is a restart, WeldStartService initiates re-deploy hence we do nothing here
            return;
        }

        final ExecutorService executor = executorService.getValue();
        final Runnable task = new Runnable() {
            // we want to execute StabilityMonitor.awaitStability() in a different thread so that this is non-blocking
            @Override
            public void run() {
                StabilityMonitor monitor = new StabilityMonitor();
                for (ServiceController controller : serviceControllers) {
                    monitor.addController(controller);
                }
                try {
                    monitor.awaitStability();
                } catch (InterruptedException ex) {
                    context.failed(new StartException(ex));
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                } finally {
                    monitor.clear();
                }
                ClassLoader oldTccl = WildFlySecurityManager.getCurrentContextClassLoaderPrivileged();
                try {
                    WildFlySecurityManager.setCurrentContextClassLoaderPrivileged(classLoader);
                    bootstrap.getValue().getBootstrap().endInitialization();
                } finally {
                    WildFlySecurityManager.setCurrentContextClassLoaderPrivileged(oldTccl);
                    context.complete();
                }
            }
        };
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            task.run();
        } finally {
            context.asynchronous();
        }
    }

    @Override
    public void stop(StopContext context) {
        // No-op
    }

    @Override
    public WeldStartCompletionService getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    public InjectedValue<WeldBootstrapService> getBootstrap() {
        return bootstrap;
    }

    public InjectedValue<ExecutorService> getServerExecutor() {
        return executorService;
    }

}
