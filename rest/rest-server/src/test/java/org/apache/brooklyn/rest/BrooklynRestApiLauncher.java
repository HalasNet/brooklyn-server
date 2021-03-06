/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.rest;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import javax.servlet.Filter;

import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampPlatformLauncherAbstract;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampPlatformLauncherNoServer;
import org.apache.brooklyn.core.BrooklynFeatureEnablement;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.server.BrooklynServerConfig;
import org.apache.brooklyn.core.server.BrooklynServiceAttributes;
import org.apache.brooklyn.rest.filter.CorsImplSupplierFilter;
import org.apache.brooklyn.rest.filter.CsrfTokenFilter;
import org.apache.brooklyn.rest.filter.EntitlementContextFilter;
import org.apache.brooklyn.rest.filter.HaHotCheckResourceFilter;
import org.apache.brooklyn.rest.filter.LoggingFilter;
import org.apache.brooklyn.rest.filter.NoCacheFilter;
import org.apache.brooklyn.rest.filter.RequestTaggingFilter;
import org.apache.brooklyn.rest.filter.RequestTaggingRsFilter;
import org.apache.brooklyn.rest.security.jaas.BrooklynLoginModule.RolePrincipal;
import org.apache.brooklyn.rest.security.jaas.JaasUtils;
import org.apache.brooklyn.rest.security.provider.AnyoneSecurityProvider;
import org.apache.brooklyn.rest.security.provider.SecurityProvider;
import org.apache.brooklyn.rest.util.ManagementContextProvider;
import org.apache.brooklyn.rest.util.ServerStoppingShutdownHandler;
import org.apache.brooklyn.rest.util.ShutdownHandlerProvider;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.net.Networking;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.text.WildcardGlobs;
import org.eclipse.jetty.jaas.JAASLoginService;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.reflections.util.ClasspathHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

/** Convenience and demo for launching programmatically. Also used for automated tests.
 * <p>
 * BrooklynLauncher has a more full-featured CLI way to start, 
 * but if you want more control you can:
 * <li> take the WAR this project builds (REST API) -- NB probably want the unshaded one (containing all deps)
 * <li> take the WAR from the brooklyn-jsgui project (brooklyn-ui repo) _and_ this WAR and combine them
 *      (this one should run as a filter on the others, _not_ as a ResourceCollection where they fight over who's got root)
 * <li> programmatically install things, following the examples herein; 
 *      in particular {@link RestApiSetup} is quite handy!
 * <p>
 * You can also just run this class. In most installs it just works, assuming your IDE or maven-fu gives you the classpath.
 * Add more apps and entities on the classpath and they'll show up in the catalog.
 **/
public class BrooklynRestApiLauncher {

    private static final Logger log = LoggerFactory.getLogger(BrooklynRestApiLauncher.class);
    final static int FAVOURITE_PORT = 8081;
    public static final String SCANNING_CATALOG_BOM_URL = "classpath://brooklyn/scanning.catalog.bom";

    enum StartMode {
        SERVLET, /** web-xml is not fully supported */ @Beta WEB_XML
    }

    public static final List<Class<? extends Filter>> DEFAULT_FILTERS = ImmutableList.<Class<? extends Filter>>of(
            RequestTaggingFilter.class,
            LoggingFilter.class);

    private boolean forceUseOfDefaultCatalogWithJavaClassPath = false;
    private Class<? extends SecurityProvider> securityProvider;
    private List<Class<? extends Filter>> filters = DEFAULT_FILTERS;
    private StartMode mode = StartMode.SERVLET;
    private ManagementContext mgmt;
    private ContextHandler customContext;
    private boolean deployJsgui = true;
    private boolean disableHighAvailability = true;
    private ServerStoppingShutdownHandler shutdownListener;

    protected BrooklynRestApiLauncher() {}

    public BrooklynRestApiLauncher managementContext(ManagementContext mgmt) {
        this.mgmt = mgmt;
        return this;
    }

    public BrooklynRestApiLauncher forceUseOfDefaultCatalogWithJavaClassPath(boolean forceUseOfDefaultCatalogWithJavaClassPath) {
        this.forceUseOfDefaultCatalogWithJavaClassPath = forceUseOfDefaultCatalogWithJavaClassPath;
        return this;
    }

    /**
     * Note: Lost on brooklyn.properties reload
     */
    public BrooklynRestApiLauncher securityProvider(Class<? extends SecurityProvider> securityProvider) {
        this.securityProvider = securityProvider;
        return this;
    }

    /**
     * Runs the server with the given set of filters. 
     * Overrides any previously supplied set (or {@link #DEFAULT_FILTERS} which is used by default).
     */
    public BrooklynRestApiLauncher filters(@SuppressWarnings("unchecked") Class<? extends Filter>... filters) {
        this.filters = Lists.newArrayList(filters);
        return this;
    }

    public BrooklynRestApiLauncher mode(StartMode mode) {
        this.mode = checkNotNull(mode, "mode");
        return this;
    }

    /** Overrides start mode to use an explicit context */
    public BrooklynRestApiLauncher customContext(ContextHandler customContext) {
        this.customContext = checkNotNull(customContext, "customContext");
        return this;
    }

    public BrooklynRestApiLauncher withJsgui() {
        this.deployJsgui = true;
        return this;
    }

    public BrooklynRestApiLauncher withoutJsgui() {
        this.deployJsgui = false;
        return this;
    }

    public BrooklynRestApiLauncher disableHighAvailability(boolean value) {
        this.disableHighAvailability = value;
        return this;
    }

    public Server start() {
        if (this.mgmt == null) {
            mgmt = new LocalManagementContext();
        }
        BrooklynCampPlatformLauncherAbstract platform = new BrooklynCampPlatformLauncherNoServer()
                .useManagementContext(mgmt)
                .launch();
        ((LocalManagementContext)mgmt).noteStartupComplete();
        log.debug("started "+platform);

        ContextHandler context;
        String summary;
        if (customContext == null) {
            switch (mode) {
            case WEB_XML:
                context = webXmlContextHandler(mgmt);
                summary = "from WAR at " + ((WebAppContext) context).getWar();
                break;
            case SERVLET:
            default:
                context = servletContextHandler(mgmt);
                summary = "programmatic Jersey ServletContainer servlet";
                break;
            }
        } else {
            context = customContext;
            summary = (context instanceof WebAppContext)
                    ? "from WAR at " + ((WebAppContext) context).getWar()
                    : "from custom context";
        }

        Maybe<Object> configSecurityProvider = mgmt.getConfig().getConfigLocalRaw(BrooklynWebConfig.SECURITY_PROVIDER_CLASSNAME);
        boolean hasConfigSecurityProvider = configSecurityProvider.isPresent();
        boolean hasOverrideSecurityProvider = securityProvider != null;
        boolean hasAnyoneOverrideSecurityProvide = (securityProvider == AnyoneSecurityProvider.class) ||
            (!hasOverrideSecurityProvider && hasConfigSecurityProvider && AnyoneSecurityProvider.class.getName().equals(configSecurityProvider.get()));
        if (!hasAnyoneOverrideSecurityProvide && (hasOverrideSecurityProvider || hasConfigSecurityProvider)) {
            ((WebAppContext)context).addOverrideDescriptor(getClass().getResource("/web-security.xml").toExternalForm());
            if (hasOverrideSecurityProvider) {
                ((BrooklynProperties) mgmt.getConfig()).put(
                        BrooklynWebConfig.SECURITY_PROVIDER_CLASSNAME, securityProvider.getName());
            }
        } else if (context instanceof WebAppContext) {
            ((WebAppContext)context).setSecurityHandler(new NopSecurityHandler());
        }

        if (forceUseOfDefaultCatalogWithJavaClassPath) {
            // sets URLs for a surefire
            ((BrooklynProperties) mgmt.getConfig()).put(BrooklynServerConfig.BROOKLYN_CATALOG_URL, SCANNING_CATALOG_BOM_URL);
            ((LocalManagementContext) mgmt).setBaseClassPathForScanning(ClasspathHelper.forJavaClassPath());
        } else {
            // don't use any catalog.xml which is set
            ((BrooklynProperties) mgmt.getConfig()).put(BrooklynServerConfig.BROOKLYN_CATALOG_URL, ManagementContextInternal.EMPTY_CATALOG_URL);
        }

        Server server = startServer(mgmt, context, summary, disableHighAvailability);
        if (shutdownListener!=null) {
            // not available in some modes, eg webapp
            shutdownListener.setServer(server);
        }
        return server;
    }

    private WebAppContext servletContextHandler(ManagementContext managementContext) {
        WebAppContext context = new WebAppContext();

        context.setAttribute(BrooklynServiceAttributes.BROOKLYN_MANAGEMENT_CONTEXT, managementContext);

        installWar(context);
        ImmutableList.Builder<Object> providersListBuilder = ImmutableList.builder();
        providersListBuilder.add(
                new ManagementContextProvider(),
                new ShutdownHandlerProvider(shutdownListener),
                new RequestTaggingRsFilter(),
                new NoCacheFilter(),
                new HaHotCheckResourceFilter(),
                new EntitlementContextFilter(),
                new CsrfTokenFilter());
        if (BrooklynFeatureEnablement.isEnabled(BrooklynFeatureEnablement.FEATURE_CORS_CXF_PROPERTY)) {
            providersListBuilder.add(new CorsImplSupplierFilter(managementContext));
        }
        RestApiSetup.installRest(context,
                providersListBuilder.build().toArray());
        
        RestApiSetup.installServletFilters(context, this.filters);

        context.setContextPath("/");
        return context;
    }

    private void installWar(WebAppContext context) {
        // here we run with the JS GUI, for convenience, if we can find it, else set up an empty dir
        // TODO pretty sure there is an option to monitor this dir and load changes to static content
        // NOTE: When running Brooklyn from an IDE (i.e. by launching BrooklynJavascriptGuiLauncher.main())
        // you will need to ensure that the working directory is set to the brooklyn-ui repo folder. For IntelliJ,
        // set the 'Working directory' of the Run/Debug Configuration to $MODULE_DIR$/brooklyn-server/launcher.
        // For Eclipse, use the default option of ${workspace_loc:brooklyn-launcher}.
        // If the working directory is not set correctly, Brooklyn will be unable to find the jsgui .war
        // file and the 'gui not available' message will be shown.
        context.setWar(this.deployJsgui && 
                findJsguiWebappInSource().isPresent() 
                    ? findJsguiWebappInSource().get()
                : ResourceUtils.create(this).doesUrlExist("classpath://brooklyn.war") 
                    ? Os.writeToTempFile(ResourceUtils.create(this).getResourceFromUrl("classpath://brooklyn.war"), "brooklyn", "war").getAbsolutePath()
                : createTempWebDirWithIndexHtml("Brooklyn REST API <p> (gui not available)"));
    }

    /** NB: not fully supported; use one of the other {@link StartMode}s */
    private WebAppContext webXmlContextHandler(ManagementContext mgmt) {
        RestApiSetup.initSwagger();
        WebAppContext context;
        if (findMatchingFile("src/main/webapp")!=null) {
            // running in source mode; need to use special classpath
            context = new WebAppContext("src/main/webapp", "/");
            context.setExtraClasspath("./target/classes");
        } else if (findRestApiWar()!=null) {
            context = new WebAppContext(findRestApiWar(), "/");
        } else {
            throw new IllegalStateException("Cannot find WAR for REST API. Expected in target/*.war, Maven repo, or in source directories.");
        }
        context.setAttribute(BrooklynServiceAttributes.BROOKLYN_MANAGEMENT_CONTEXT, mgmt);
        // TODO shutdown hook
        
        return context;
    }

    private static Server startServer(ManagementContext mgmt, ContextHandler context, String summary, boolean disableHighAvailability) {
        // TODO this repeats code in BrooklynLauncher / WebServer. should merge the two paths.
        boolean secure = mgmt != null && !BrooklynWebConfig.hasNoSecurityOptions(mgmt.getConfig());
        if (secure) {
            log.debug("Detected security configured, launching server on all network interfaces");
        } else {
            log.debug("Detected no security configured, launching server on loopback (localhost) network interface only");
            if (mgmt!=null) {
                log.debug("Detected no security configured, running on loopback; disabling authentication");
                ((BrooklynProperties)mgmt.getConfig()).put(BrooklynWebConfig.SECURITY_PROVIDER_CLASSNAME, AnyoneSecurityProvider.class.getName());
            }
        }
        if (mgmt != null && disableHighAvailability)
            mgmt.getHighAvailabilityManager().disabled();
        InetSocketAddress bindLocation = new InetSocketAddress(
                secure ? Networking.ANY_NIC : Networking.LOOPBACK,
                        Networking.nextAvailablePort(FAVOURITE_PORT));
        return startServer(mgmt, context, summary, bindLocation);
    }

    private static Server startServer(ManagementContext mgmt, ContextHandler context, String summary, InetSocketAddress bindLocation) {
        Server server = new Server(bindLocation);

        initJaas(mgmt, server);

        server.setHandler(context);
        try {
            server.start();
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
        log.info("Brooklyn REST server started ("+summary+") on");
        log.info("  http://localhost:"+((NetworkConnector)server.getConnectors()[0]).getLocalPort()+"/");

        return server;
    }

    // TODO Why parallel code for server init here and in BrooklynWebServer?
    private static void initJaas(ManagementContext mgmt, Server server) {
        JaasUtils.init(mgmt);
        initJaasLoginService(server);
    }

    public static void initJaasLoginService(Server server) {
        JAASLoginService loginService = new JAASLoginService();
        loginService.setName("webconsole");
        loginService.setLoginModuleName("webconsole");
        loginService.setRoleClassNames(new String[] {RolePrincipal.class.getName()});
        server.addBean(loginService);
    }

    public static BrooklynRestApiLauncher launcher() {
        return new BrooklynRestApiLauncher();
    }

    public static void main(String[] args) throws Exception {
        startRestResourcesViaServlet();
        log.info("Press Ctrl-C to quit.");
    }

    public static BrooklynRestApiLauncher launcherServlet() {
        return new BrooklynRestApiLauncher().mode(StartMode.SERVLET);
    }
    
    public static Server startRestResourcesViaServlet() throws Exception {
        return launcherServlet().start();
    }

    public static BrooklynRestApiLauncher launcherWebXml() {
        return new BrooklynRestApiLauncher().mode(StartMode.WEB_XML);
    }
    
    public static Server startRestResourcesViaWebXml() throws Exception {
        return launcherWebXml().start();
    }

    /** look for the JS GUI webapp in common source places, returning path to it if found, or null.
     * assumes `brooklyn-ui` is checked out as a sibling to `brooklyn-server`, and both are 2, 3, 1, or 0
     * levels above the CWD. */
    @Beta
    public static Maybe<String> findJsguiWebappInSource() {
    	// normally up 2 levels to where brooklyn-* folders are, then into ui
    	// (but in rest projects it might be 3 up, and in some IDEs we might run from parent dirs.)
        // TODO could also look in maven repo ?
    	return findFirstMatchingFile(
    			"../../brooklyn-ui/src/main/webapp",
    			"../../../brooklyn-ui/src/main/webapp",
    			"../brooklyn-ui/src/main/webapp",
    			"./brooklyn-ui/src/main/webapp",
    			"../../brooklyn-ui/target/*.war",
    			"../../..brooklyn-ui/target/*.war",
    			"../brooklyn-ui/target/*.war",
    			"./brooklyn-ui/target/*.war");
    }

    /** look for the REST WAR file in common places, returning path to it if found, or null */
    private static String findRestApiWar() {
        // don't look at src/main/webapp here -- because classes won't be there!
        // could also look in maven repo ?
    	// TODO looks like this stopped working at runtime a long time ago;
    	// only needed for WEB_XML mode, and not used, but should remove or check?
    	// (probably will be superseded by CXF/OSGi work however)
        return findMatchingFile("../rest/target/*.war").orNull();
    }

    /** as {@link #findMatchingFile(String)} but finding the first */
    public static Maybe<String> findFirstMatchingFile(String ...filenames) {
    	for (String f: filenames) {
    		Maybe<String> result = findMatchingFile(f);
    		if (result.isPresent()) return result;
    	}
    	return Maybe.absent();
    }
    
    /** returns the supplied filename if it exists (absolute or relative to the current directory);
     * supports globs in the filename portion only, in which case it returns the _newest_ matching file.
     * <p>
     * otherwise returns null */
    @Beta // public because used in dependent test projects
    public static Maybe<String> findMatchingFile(String filename) {
        final File f = new File(filename);
        if (f.exists()) return Maybe.of(filename);
        File dir = f.getParentFile();
        File result = null;
        if (dir.exists()) {
            File[] matchingFiles = dir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return WildcardGlobs.isGlobMatched(f.getName(), name);
                }
            });
            for (File mf: matchingFiles) {
                if (result==null || mf.lastModified() > result.lastModified()) result = mf;
            }
        }
        if (result==null) return Maybe.absent();
        return Maybe.of(result.getAbsolutePath());
    }

    /** create a directory with a simple index.html so we have some content being served up */
    private static String createTempWebDirWithIndexHtml(String indexHtmlContent) {
        File dir = Files.createTempDir();
        dir.deleteOnExit();
        try {
            Files.write(indexHtmlContent, new File(dir, "index.html"), Charsets.UTF_8);
        } catch (IOException e) {
            Exceptions.propagate(e);
        }
        return dir.getAbsolutePath();
    }
    
}
