/*
 * JBoss, Home of Professional Open Source.
 * Copyright (c) 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
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

package org.jboss.as.test.integration.ejb.security;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.arquillian.api.ContainerResource;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.test.integration.ejb.security.authorization.SaslLegacyMechanismBean;
import org.jboss.as.test.integration.ejb.security.authorization.SaslLegacyMechanismBeanRemote;
import org.jboss.as.test.integration.management.util.ServerReload;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.naming.Context;
import javax.naming.InitialContext;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUCCESS;


/**
 * Security is configured via legacy security options in remoting subsystem. Test asserts, that anonymous authentication/plain authentication is used.
 *
 *  @author Jiri Ondrusek (jondruse@redhat.com)
 */
@RunWith(Arquillian.class)
@RunAsClient
@ServerSetup(SaslLegacyMechanismConfigurationTestCase.LegacyMechanismConfigurationSetupTask.class)
public class SaslLegacyMechanismConfigurationTestCase {

   private static final Logger log = Logger.getLogger(SaslLegacyMechanismConfigurationTestCase.class);
   private static final String MODULE = "SaslLegacyMechanismConfigurationTestCase";

   @ContainerResource
   private ManagementClient managementClient;


   @Deployment(name = MODULE + ".jar", order = 1, testable = false)
   public static Archive<JavaArchive> testAppDeployment() {
      final Package currentPackage = SaslLegacyMechanismConfigurationTestCase.class.getPackage();

      final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, MODULE + ".jar")
              .addClass(SaslLegacyMechanismBean.class)
              .addClass(SaslLegacyMechanismBeanRemote.class);
      return jar;
   }

   @Test
   public void testAnonymous() throws Exception {
      String echoValue = getBean(log, null, null).getPrincipal();
      Assert.assertEquals("anonymous", echoValue);
   }

   @Test
   public void testAuthorized() throws Exception {
      String echoValue = getBean(log, "user1", "password1").getPrincipal();
      Assert.assertEquals("user1", echoValue);
   }

   // ejb client code

   private SaslLegacyMechanismBeanRemote getBean(final Logger log, String username, String password) throws Exception {
      log.trace("**** creating InitialContext");
      InitialContext ctx = new InitialContext(setupEJBClientProperties(username, password));
      try {
         log.trace("**** looking up StatelessBean through JNDI");
         SaslLegacyMechanismBeanRemote bean = (SaslLegacyMechanismBeanRemote)
                 ctx.lookup("ejb:/" + MODULE + "/" + SaslLegacyMechanismBean.class.getSimpleName() + "!" + SaslLegacyMechanismBeanRemote.class.getCanonicalName());
         return bean;
      } finally {
         ctx.close();
      }
   }

   private Properties setupEJBClientProperties(String username, String password) throws IOException {
      log.trace("*** reading EJBClientContextSelector properties");
      // setup the properties
      final String clientPropertiesFile = "org/jboss/as/test/integration/ejb/security/jboss-ejb-client.properties";
      final InputStream inputStream = SaslLegacyMechanismConfigurationTestCase.class.getClassLoader().getResourceAsStream(clientPropertiesFile);
      if (inputStream == null) {
         throw new IllegalStateException("Could not find " + clientPropertiesFile + " in classpath");
      }
      final Properties properties = new Properties();
      properties.load(inputStream);

      properties.put(Context.URL_PKG_PREFIXES, "org.jboss.ejb.client.naming");

      if(username != null && password != null) {
         properties.put(Context.SECURITY_PRINCIPAL, username);
         properties.put(Context.SECURITY_CREDENTIALS, password);
      }
      return properties;
   }

   /**
    * Setup task which adds legacy remoting properties and restores it afterwards.
    */
   public static class LegacyMechanismConfigurationSetupTask implements ServerSetupTask {

      private ModelNode localAuthentication, propsAuthentication;

      private static final PathAddress AUTHENTICATION_PROPS = PathAddress.pathAddress(ModelDescriptionConstants.CORE_SERVICE, "management")
              .append("security-realm", "ApplicationRealm").append("authentication", "properties");


      private static final PathAddress LOCAL_AUTHENTICATION = PathAddress.pathAddress(ModelDescriptionConstants.CORE_SERVICE, "management")
              .append("security-realm", "ApplicationRealm").append("authentication", "local");

      private static final PathAddress SASL_MECHANISMS = PathAddress.pathAddress(ModelDescriptionConstants.SUBSYSTEM, "remoting")
              .append("http-connector", "http-remoting-connector").append("property", "SASL_MECHANISMS");

      private static final PathAddress SASL_POLICY_NOANONYMOUS = PathAddress.pathAddress(ModelDescriptionConstants.SUBSYSTEM, "remoting")
              .append("http-connector", "http-remoting-connector").append("property", "SASL_POLICY_NOANONYMOUS");

      @Override
      public void setup(ManagementClient managementClient, String containerId) throws Exception {
         ModelControllerClient mcc = managementClient.getControllerClient();

         ModelNode authentication = execute(Operations.createReadResourceOperation(LOCAL_AUTHENTICATION.append().toModelNode()), mcc);
         Assert.assertEquals(authentication.toString(), SUCCESS, authentication.get(OUTCOME).asString());
         localAuthentication = authentication.get(RESULT);

         authentication = execute(Operations.createReadResourceOperation(AUTHENTICATION_PROPS.append().toModelNode()), mcc);
         Assert.assertEquals(authentication.toString(), SUCCESS, authentication.get(OUTCOME).asString());
         propsAuthentication = authentication.get(RESULT);

         ModelNode compositeOperation = Operations.createCompositeOperation();
         compositeOperation.get(ModelDescriptionConstants.STEPS).add(Operations.createRemoveOperation(LOCAL_AUTHENTICATION.toModelNode()));
         compositeOperation.get(ModelDescriptionConstants.STEPS).add(addSaslMechanisms());
         compositeOperation.get(ModelDescriptionConstants.STEPS).add(addPolicyNoanonymous());

         String users = new File(SaslLegacyMechanismConfigurationTestCase.class.getResource("users.properties").getFile()).getAbsolutePath();
         compositeOperation.get(ModelDescriptionConstants.STEPS).add(Operations.createWriteAttributeOperation(AUTHENTICATION_PROPS.toModelNode(),"path", users));
         compositeOperation.get(ModelDescriptionConstants.STEPS).add(Operations.createWriteAttributeOperation(AUTHENTICATION_PROPS.toModelNode(),"plain-text", "true"));
         compositeOperation.get(ModelDescriptionConstants.STEPS).add(Operations.createUndefineAttributeOperation(AUTHENTICATION_PROPS.toModelNode(),"relative-to"));


         ModelNode response = execute(compositeOperation, mcc);
         Assert.assertEquals(response.toString(), SUCCESS, response.get(OUTCOME).asString());

         ServerReload.reloadIfRequired(managementClient.getControllerClient());
      }

      @Override
      public void tearDown(ManagementClient managementClient, String containerId) throws Exception {
         ModelControllerClient mcc = managementClient.getControllerClient();

         ModelNode compositeOperation = Operations.createCompositeOperation();
         compositeOperation.get(ModelDescriptionConstants.STEPS).add(addLocalAuthentication());
         compositeOperation.get(ModelDescriptionConstants.STEPS).add(Operations.createRemoveOperation(SASL_MECHANISMS.toModelNode()));
         compositeOperation.get(ModelDescriptionConstants.STEPS).add(addPropsAuthentication());
         compositeOperation.get(ModelDescriptionConstants.STEPS).add(Operations.createRemoveOperation(SASL_MECHANISMS.toModelNode()));
         compositeOperation.get(ModelDescriptionConstants.STEPS).add(Operations.createRemoveOperation(SASL_POLICY_NOANONYMOUS.toModelNode()));


         ModelNode response = execute(compositeOperation, mcc);
         Assert.assertEquals(response.toString(), SUCCESS, response.get(OUTCOME).asString());

         ServerReload.reloadIfRequired(managementClient.getControllerClient());
      }

      private ModelNode addLocalAuthentication() throws IOException {
         ModelNode addOperation = Operations.createAddOperation(LOCAL_AUTHENTICATION.toModelNode());
         for (String attribute : localAuthentication.keys() ) {
            addOperation.get(attribute).set(localAuthentication.get(attribute).asString());
         }
         return addOperation;
      }

      private ModelNode addPropsAuthentication() throws IOException {
         ModelNode addOperation = Operations.createAddOperation(AUTHENTICATION_PROPS.toModelNode());
         for (String attribute : propsAuthentication.keys() ) {
            addOperation.get(attribute).set(propsAuthentication.get(attribute).asString());
         }
         return addOperation;
      }

      private ModelNode addSaslMechanisms() throws IOException {
         ModelNode addRaOperation = Operations.createAddOperation(SASL_MECHANISMS.toModelNode());
         addRaOperation.get("name").set("value");
         addRaOperation.get("value").set("PLAIN,ANONYMOUS");
         return addRaOperation;
      }

      private ModelNode addPolicyNoanonymous() throws IOException {
         ModelNode addRaOperation = Operations.createAddOperation(SASL_POLICY_NOANONYMOUS.toModelNode());
         addRaOperation.get("name").set("value");
         addRaOperation.get("value").set("false");
         return addRaOperation;
      }

      private ModelNode execute(ModelNode operation, ModelControllerClient client) throws IOException {
         return client.execute(operation);
      }
   }
}