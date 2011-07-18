/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
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
package org.infinispan.test.fwk;

import org.infinispan.config.Configuration;
import org.infinispan.config.GlobalConfiguration;
import org.infinispan.config.InfinispanConfiguration;
import org.infinispan.jmx.PerThreadMBeanServerLookup;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.infinispan.util.LegacyKeySupportSystemProperties;
import org.infinispan.util.Util;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.infinispan.test.fwk.JGroupsConfigBuilder.getJGroupsConfig;

/**
 * CacheManagers in unit tests should be created with this factory, in order to avoid resource clashes. See
 * http://community.jboss.org/wiki/ParallelTestSuite for more details.
 *
 * @author Mircea.Markus@jboss.com
 * @author Galder Zamarreño
 */
public class TestCacheManagerFactory {


   private static AtomicInteger jmxDomainPostfix = new AtomicInteger();

   public static final String MARSHALLER = LegacyKeySupportSystemProperties.getProperty("infinispan.test.marshaller.class", "infinispan.marshaller.class");
   private static final Log log = LogFactory.getLog(TestCacheManagerFactory.class);

   private static ThreadLocal<PerThreadCacheManagers> perThreadCacheManagers = new ThreadLocal<PerThreadCacheManagers>() {
      @Override
      protected PerThreadCacheManagers initialValue() {
         return new PerThreadCacheManagers();
      }
   };

   private static DefaultCacheManager newDefaultCacheManager(boolean start, GlobalConfiguration gc, Configuration c, boolean keepJmxDomain) {
      if (!keepJmxDomain) {
         gc.setJmxDomain("infinispan" + jmxDomainPostfix.incrementAndGet());
      }
      return newDefaultCacheManager(start, gc, c);
   }

   public static EmbeddedCacheManager fromXml(String xmlFile, boolean allowDupeDomains) throws IOException {
      InfinispanConfiguration parser = InfinispanConfiguration.newInfinispanConfiguration(
            xmlFile,
            InfinispanConfiguration.resolveSchemaPath(),
            Thread.currentThread().getContextClassLoader());
      return fromConfigFileParser(parser, allowDupeDomains);
   }

   public static EmbeddedCacheManager fromXml(String xmlFile) throws IOException {
      return fromXml(xmlFile, false);
   }

   public static EmbeddedCacheManager fromStream(InputStream is) throws IOException {
      return fromStream(is, false);
   }

   public static EmbeddedCacheManager fromStream(InputStream is, boolean allowDupeDomains) throws IOException {
      InfinispanConfiguration parser = InfinispanConfiguration.newInfinispanConfiguration(
            is, InfinispanConfiguration.findSchemaInputStream());
      return fromConfigFileParser(parser, allowDupeDomains);
   }

   private static EmbeddedCacheManager fromConfigFileParser(InfinispanConfiguration parser, boolean allowDupeDomains) {
      GlobalConfiguration gc = parser.parseGlobalConfiguration();
      if (allowDupeDomains) gc.setAllowDuplicateDomains(true);
      Map<String, Configuration> named = parser.parseNamedConfigurations();
      Configuration c = parser.parseDefaultConfiguration();

      minimizeThreads(gc);
      amendTransport(gc);

      EmbeddedCacheManager cm = newDefaultCacheManager(true, gc, c, false);
      for (Map.Entry<String, Configuration> e : named.entrySet()) cm.defineConfiguration(e.getKey(), e.getValue());
      cm.start();
      return cm;
   }

   /**
    * Creates an cache manager that does not support clustering or transactions.
    */
   public static EmbeddedCacheManager createLocalCacheManager() {
      return createLocalCacheManager(false);
   }

   /**
    * Creates an cache manager that does not support clustering.
    *
    * @param transactional if true, the cache manager will support transactions by default.
    */
   public static EmbeddedCacheManager createLocalCacheManager(boolean transactional) {
      return createLocalCacheManager(transactional, -1);
   }

   public static EmbeddedCacheManager createLocalCacheManager(boolean transactional, long lockAcquisitionTimeout) {
      GlobalConfiguration globalConfiguration = GlobalConfiguration.getNonClusteredDefault();
      amendMarshaller(globalConfiguration);
      minimizeThreads(globalConfiguration);
      Configuration c = new Configuration();
      if (lockAcquisitionTimeout > -1) c.setLockAcquisitionTimeout(lockAcquisitionTimeout);
      if (transactional) amendJTA(c);
      return newDefaultCacheManager(true, globalConfiguration, c, false);
   }

   private static void amendJTA(Configuration c) {
      c.setTransactionManagerLookupClass(TransactionSetup.getManagerLookup());
   }

   /**
    * Creates an cache manager that does support clustering.
    */
   public static EmbeddedCacheManager createClusteredCacheManager() {
      return createClusteredCacheManager(false);
   }

   public static EmbeddedCacheManager createClusteredCacheManager(boolean withFD) {
      return createClusteredCacheManager(withFD, new Configuration(), false);
   }

   /**
    * Creates an cache manager that does support clustering with a given default cache configuration.
    */
   public static EmbeddedCacheManager createClusteredCacheManager(Configuration defaultCacheConfig) {
      return createClusteredCacheManager(defaultCacheConfig, false);
   }

   public static EmbeddedCacheManager createClusteredCacheManager(Configuration defaultCacheConfig, boolean transactional) {
      return createClusteredCacheManager(false, defaultCacheConfig, transactional);
   }

   public static EmbeddedCacheManager createClusteredCacheManager(
         boolean withFD, Configuration defaultCacheConfig, boolean transactional) {
      GlobalConfiguration globalConfiguration = GlobalConfiguration.getClusteredDefault();
      amendMarshaller(globalConfiguration);
      minimizeThreads(globalConfiguration);
      amendTransport(globalConfiguration, withFD);
      if (transactional) amendJTA(defaultCacheConfig);
      return newDefaultCacheManager(true, globalConfiguration, defaultCacheConfig, false);
   }

   /**
    * Creates a cache manager and amends the supplied configuration in order to avoid conflicts (e.g. jmx, jgroups)
    * during running tests in parallel.
    */
   public static EmbeddedCacheManager createCacheManager(GlobalConfiguration configuration) {
      return internalCreateJmxDomain(true, configuration, false);
   }

   public static EmbeddedCacheManager createCacheManager(boolean start, GlobalConfiguration configuration) {
      return internalCreateJmxDomain(start, configuration, false);
   }

   /**
    * Creates a cache manager that won't try to modify the configured jmx domain name: {@link
    * org.infinispan.config.GlobalConfiguration#getJmxDomain()}}. This method must be used with care, and one should
    * make sure that no domain name collision happens when the parallel suite executes. An approach to ensure this, is
    * to set the domain name to the name of the test class that instantiates the CacheManager.
    */
   public static EmbeddedCacheManager createCacheManagerEnforceJmxDomain(GlobalConfiguration configuration) {
      return internalCreateJmxDomain(true, configuration, true);
   }

   private static EmbeddedCacheManager internalCreateJmxDomain(boolean start, GlobalConfiguration configuration, boolean enforceJmxDomain) {
      amendMarshaller(configuration);
      minimizeThreads(configuration);
      amendTransport(configuration);
      return newDefaultCacheManager(start, configuration, new Configuration(), enforceJmxDomain);
   }

   public static EmbeddedCacheManager createCacheManager(Configuration.CacheMode mode, boolean indexing) {
      GlobalConfiguration gc = mode.isClustered() ? GlobalConfiguration.getClusteredDefault() : GlobalConfiguration.getNonClusteredDefault();
      Configuration c = new Configuration();
      if (indexing) c.setIndexingEnabled(true);
      c.setCacheMode(mode);
      return createCacheManager(gc, c);
   }

   /**
    * Creates a local cache manager and amends so that it won't conflict (e.g. jmx) with other managers whilst running
    * tests in parallel.  This is a non-transactional cache manager.
    */
   public static EmbeddedCacheManager createCacheManager(Configuration defaultCacheConfig) {
      if (defaultCacheConfig.getTransactionManagerLookup() != null || defaultCacheConfig.getTransactionManagerLookupClass() != null) {
         log.error("You have passed in a default configuration which has transactional elements set.  If you wish to use transactions, use the TestCacheManagerFactory.createCacheManager(Configuration defaultCacheConfig, boolean transactional) method.");
      }
      defaultCacheConfig.setTransactionManagerLookup(null);
      defaultCacheConfig.setTransactionManagerLookupClass(null);
      return createCacheManager(defaultCacheConfig, false);
   }

   public static EmbeddedCacheManager createCacheManager(Configuration defaultCacheConfig, boolean transactional) {
      GlobalConfiguration globalConfiguration;
      if (defaultCacheConfig.getCacheMode().isClustered()) {
         globalConfiguration = GlobalConfiguration.getClusteredDefault();
         amendTransport(globalConfiguration);
      }
      else {
         globalConfiguration = GlobalConfiguration.getNonClusteredDefault();
      }
      amendMarshaller(globalConfiguration);
      minimizeThreads(globalConfiguration);
      if (transactional) amendJTA(defaultCacheConfig);

      // we stop caches during transactions all the time
      // so wait at most 1 second for ongoing transactions when stopping
      defaultCacheConfig.fluent().cacheStopTimeout(1000);

      return newDefaultCacheManager(true, globalConfiguration, defaultCacheConfig, false);
   }

   public static EmbeddedCacheManager createCacheManager(GlobalConfiguration configuration, Configuration defaultCfg) {
      return createCacheManager(configuration, defaultCfg, false, false);
   }

   public static EmbeddedCacheManager createCacheManager(GlobalConfiguration configuration, Configuration defaultCfg, boolean transactional) {
      minimizeThreads(configuration);
      amendMarshaller(configuration);
      amendTransport(configuration);
      if (transactional) amendJTA(defaultCfg);
      return newDefaultCacheManager(true, configuration, defaultCfg, false);
   }

   public static EmbeddedCacheManager createCacheManager(GlobalConfiguration configuration, Configuration defaultCfg, boolean transactional, boolean keepJmxDomainName) {
      return createCacheManager(configuration, defaultCfg, transactional, keepJmxDomainName, false);
   }

   public static EmbeddedCacheManager createCacheManager(GlobalConfiguration configuration, Configuration defaultCfg, boolean transactional, boolean keepJmxDomainName, boolean dontFixTransport) {
      minimizeThreads(configuration);
      amendMarshaller(configuration);
      if (!dontFixTransport) amendTransport(configuration);
      if (transactional) amendJTA(defaultCfg);
      return newDefaultCacheManager(true, configuration, defaultCfg, keepJmxDomainName);
   }

   /**
    * @see #createCacheManagerEnforceJmxDomain(String)
    */
   public static EmbeddedCacheManager createCacheManagerEnforceJmxDomain(String jmxDomain) {
      return createCacheManagerEnforceJmxDomain(jmxDomain, true, true);
   }

   /**
    * @see #createCacheManagerEnforceJmxDomain(String)
    */
   public static EmbeddedCacheManager createCacheManagerEnforceJmxDomain(String jmxDomain, boolean exposeGlobalJmx, boolean exposeCacheJmx) {
      return createCacheManagerEnforceJmxDomain(jmxDomain, null, exposeGlobalJmx, exposeCacheJmx);
   }

   /**
    * @see #createCacheManagerEnforceJmxDomain(String)
    */
   public static EmbeddedCacheManager createCacheManagerEnforceJmxDomain(String jmxDomain, String cacheManagerName, boolean exposeGlobalJmx, boolean exposeCacheJmx) {
      GlobalConfiguration globalConfiguration = GlobalConfiguration.getNonClusteredDefault();
      globalConfiguration.setJmxDomain(jmxDomain);
      if (cacheManagerName != null)
         globalConfiguration.setCacheManagerName(cacheManagerName);
      globalConfiguration.setMBeanServerLookup(PerThreadMBeanServerLookup.class.getName());
      globalConfiguration.setExposeGlobalJmxStatistics(exposeGlobalJmx);
      Configuration configuration = new Configuration();
      configuration.setExposeJmxStatistics(exposeCacheJmx);
      return createCacheManager(globalConfiguration, configuration, false, true);
   }

   public static Configuration getDefaultConfiguration(boolean transactional) {
      Configuration c = new Configuration();
      if (transactional) amendJTA(c);
      return c;
   }

   public static Configuration getDefaultConfiguration(boolean transactional, Configuration.CacheMode cacheMode) {
      Configuration c = new Configuration();
      if (transactional) amendJTA(c);
      c.setCacheMode(cacheMode);
      if (cacheMode.isClustered()) {
         c.setSyncRollbackPhase(true);
         c.setSyncCommitPhase(true);
      }
      return c;
   }

   private static void amendTransport(GlobalConfiguration cfg) {
      amendTransport(cfg, false);
   }

   private static void amendTransport(GlobalConfiguration configuration, boolean withFD) {
      if (configuration.getTransportClass() != null) { //this is local
         Properties newTransportProps = new Properties();
         Properties previousSettings = configuration.getTransportProperties();
         if (previousSettings != null) {
            newTransportProps.putAll(previousSettings);
         }

         String fullTestName = perThreadCacheManagers.get().fullTestName;
         String nextCacheName = perThreadCacheManagers.get().getNextCacheName();
         if (fullTestName == null) {
            // Either we're running from within the IDE or it's a
            // @Test(timeOut=nnn) test. We rely here on some specific TestNG
            // thread naming convention which can break, but TestNG offers no
            // other alternative. It does not offer any callbacks within the
            // thread that runs the test that can timeout.
            String threadName = Thread.currentThread().getName();
            String pattern = "TestNGInvoker-";
            if (threadName.startsWith(pattern)) {
               // This is a timeout test, so for the moment rely on the test
               // method name that comes in the thread name.
               fullTestName = threadName;
               nextCacheName = threadName.substring(
                     threadName.indexOf("-") + 1, threadName.indexOf('('));
            } // else, test is being run from IDE
         }

         newTransportProps.put(JGroupsTransport.CONFIGURATION_STRING,
            getJGroupsConfig(fullTestName, withFD));

         configuration.setTransportProperties(newTransportProps);
         configuration.setTransportNodeName(nextCacheName);
      }
   }

   public static void minimizeThreads(GlobalConfiguration gc) {
      Properties p = new Properties();
      p.setProperty("maxThreads", "1");
      gc.setAsyncTransportExecutorProperties(p);
   }

   public static void amendMarshaller(GlobalConfiguration configuration) {
      if (MARSHALLER != null) {
         try {
            Util.loadClassStrict(MARSHALLER, Thread.currentThread().getContextClassLoader());
            configuration.setMarshallerClass(MARSHALLER);
         } catch (ClassNotFoundException e) {
            // No-op, stick to GlobalConfiguration default.
         }
      }
   }

   private static DefaultCacheManager newDefaultCacheManager(boolean start, GlobalConfiguration gc, Configuration c) {
      DefaultCacheManager defaultCacheManager = new DefaultCacheManager(gc, c, start);
      PerThreadCacheManagers threadCacheManagers = perThreadCacheManagers.get();
      String methodName = extractMethodName();
      log.trace("Adding DCM (" + defaultCacheManager.getAddress() + ") for method: '" + methodName + "'");
      threadCacheManagers.add(methodName, defaultCacheManager);
      return defaultCacheManager;
   }

   private static String extractMethodName() {
      StackTraceElement[] stack = Thread.currentThread().getStackTrace();
      if (stack.length == 0) return null;
      for (int i = stack.length - 1; i > 0; i--) {
         StackTraceElement e = stack[i];
         String className = e.getClassName();
         if ((className.indexOf("org.infinispan") != -1) && className.indexOf("org.infinispan.test") < 0)
            return e.toString();
      }
      return null;
   }

   static void testStarted(String testName, String fullName) {
      perThreadCacheManagers.get().setTestName(testName, fullName);
   }

   static void testFinished(String testName) {
      perThreadCacheManagers.get().checkManagersClosed(testName);
      perThreadCacheManagers.get().unsetTestName();
   }

   private static class PerThreadCacheManagers {
      String testName = null;
      private String oldThreadName;
      HashMap<EmbeddedCacheManager, String> cacheManagers = new HashMap<EmbeddedCacheManager, String>();
      String fullTestName;

      public void checkManagersClosed(String testName) {
         for (Map.Entry<EmbeddedCacheManager, String> cmEntry : cacheManagers.entrySet()) {
            if (cmEntry.getKey().getStatus().allowInvocations()) {
               String thName = Thread.currentThread().getName();
               String errorMessage = '\n' +
                     "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                     "!!!!!! (" + thName + ") Exiting because " + testName + " has NOT shut down all the cache managers it has started !!!!!!!\n" +
                     "!!!!!! (" + thName + ") The still-running cacheManager was created here: " + cmEntry.getValue() + " !!!!!!!\n" +
                     "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n";
               log.error(errorMessage);
               System.err.println(errorMessage);
               System.exit(9);
            }
         }
         cacheManagers.clear();
      }

      public String getNextCacheName() {
         int index = cacheManagers.size();
         char name = (char) ((int) 'A' + index);
         return (testName != null ? testName + "-" : "") + "Node" + name;
      }

      public void add(String methodName, DefaultCacheManager cm) {
         cacheManagers.put(cm, methodName);
      }

      public void setTestName(String testName, String fullTestName) {
         this.testName = testName;
         this.fullTestName = fullTestName;
         this.oldThreadName = Thread.currentThread().getName();
         Thread.currentThread().setName("testng-" + testName);
      }

      public void unsetTestName() {
         this.testName = null;
         Thread.currentThread().setName(oldThreadName);
         this.oldThreadName = null;
      }
   }
}
