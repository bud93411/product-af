/*
 *
 *  Copyright 2014 WSO2, Inc. (http://wso2.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.wso2.carbon.appfactory.stratos.listeners;

import org.apache.axis2.context.ConfigurationContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.common.beans.TenantInfoBean;
import org.apache.stratos.common.exception.StratosException;
import org.apache.stratos.manager.dto.SubscriptionInfo;
import org.apache.stratos.manager.manager.CartridgeSubscriptionManager;
import org.apache.stratos.manager.subscription.SubscriptionData;
import org.apache.stratos.manager.utils.ApplicationManagementUtil;
import org.apache.stratos.messaging.message.receiver.tenant.TenantManager;
import org.apache.stratos.tenant.mgt.core.TenantPersistor;
import org.apache.stratos.tenant.mgt.util.TenantMgtUtil;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.wso2.carbon.appfactory.common.AppFactoryConstants;
import org.wso2.carbon.appfactory.common.AppFactoryException;
import org.wso2.carbon.appfactory.common.beans.RuntimeBean;
import org.wso2.carbon.appfactory.common.util.AppFactoryUtil;
import org.wso2.carbon.appfactory.s4.integration.RepositoryProvider;
import org.wso2.carbon.appfactory.stratos.listeners.dto.RepositoryBean;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.core.multitenancy.utils.TenantAxisUtils;
import org.wso2.carbon.user.core.tenant.Tenant;
import org.wso2.carbon.utils.ConfigurationContextService;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import javax.jms.*;
import java.io.File;
import java.io.IOException;

/**
 * Listens to topic  in the Message Broker and when a new message arrived (for tenant creation), executes the
 * onMessage method. CLIENT_ACKNOWLEDGE is used.
 * This class is set as the Message Listener in StratosSubscriptionDurableSubscriber class.
 */
public class StratosSubscriptionMessageListener implements MessageListener {
	private static Log log = LogFactory.getLog(StratosSubscriptionMessageListener.class);
	protected TopicConnection topicConnection;
	protected TopicSession topicSession;
	protected TopicSubscriber topicSubscriber;

	public StratosSubscriptionMessageListener(TopicConnection topicConnection, TopicSession topicSession,
	                                          TopicSubscriber topicSubscriber) {
		this.topicConnection = topicConnection;
		this.topicSession = topicSession;
		this.topicSubscriber = topicSubscriber;
	}

	/**
	 * @param message - map message which contains data to stratos subscriptions via a rest call.
	 */
	@Override
	public void onMessage(Message message) {
		RuntimeBean[] runtimeBeans;
		TenantInfoBean tenantInfoBean;
		MapMessage mapMessage;
		if (message instanceof MapMessage) {
			mapMessage = (MapMessage) message;
			String tenantInfoJson = null;
			try {
				String runtimesJson = mapMessage.getString(AppFactoryConstants.RUNTIMES_INFO);
				tenantInfoJson = mapMessage.getString(AppFactoryConstants.TENANT_INFO);
				ObjectMapper mapper = new ObjectMapper();
				runtimeBeans = mapper.readValue(runtimesJson, RuntimeBean[].class);
				tenantInfoBean = mapper.readValue(tenantInfoJson, TenantInfoBean.class);
				if (log.isDebugEnabled()) {
					log.debug("Received a message for tenant domain " + tenantInfoBean.getTenantDomain());
				}
				String stage = mapMessage.getString(AppFactoryConstants.STAGE);
				if (!TenantManager.getInstance().tenantExists(tenantInfoBean.getTenantId())) {
					addTenant(tenantInfoBean);
				} else {
					if (log.isDebugEnabled()) {
						log.debug("Tenant Already added in stratos, skipping the tenant addition and continuing " +
						          "with subscription to cartridges. Tenant domain : " +
						          tenantInfoBean.getTenantDomain() + "and tenant Id : " + tenantInfoBean.getTenantId());
					}
				}
				try {
					PrivilegedCarbonContext.startTenantFlow();
					PrivilegedCarbonContext.getThreadLocalCarbonContext()
					                       .setTenantDomain(tenantInfoBean.getTenantDomain(), true);
					PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(tenantInfoBean.getAdmin());
					for (RuntimeBean runtimeBean : runtimeBeans) {
						RepositoryBean repositoryBean = createGitRepository(runtimeBean, tenantInfoBean, stage);
						subscribe(runtimeBean, tenantInfoBean, repositoryBean, getConfigContext(), stage);
					}
					if (log.isDebugEnabled()) {
						log.debug("Tenant added and subscribed to cartridges successfully for tenant : " +
						          tenantInfoBean.getTenantDomain());
					}
				} finally {
					PrivilegedCarbonContext.endTenantFlow();
				}
				mapMessage.acknowledge();
				if (log.isDebugEnabled()) {
					log.debug("Message processed successfully. Acknowledged to MB for tenant : " +
					          tenantInfoBean.getTenantDomain());
				}
			} catch (AppFactoryException e) {
				log.error("Error while subscribing tenant to stratos cartridge. tenantInfoJason : " + tenantInfoJson);
			} catch (JsonMappingException e) {
				log.error("Error while subscribing tenant to stratos cartridge. tenantInfoJason : " + tenantInfoJson);
			} catch (JsonParseException e) {
				log.error("Error while subscribing tenant to stratos cartridge. tenantInfoJason : " + tenantInfoJson);
			} catch (IOException e) {
				log.error("Error while subscribing tenant to stratos cartridge. tenantInfoJason : " + tenantInfoJson);
			} catch (JMSException e) {
				log.error("Error while subscribing tenant to stratos cartridge. tenantInfoJason : " + tenantInfoJson);
			} catch (Exception e) {
				log.error("Error while subscribing tenant to stratos cartridge. tenantInfoJason : " + tenantInfoJson);
			}
		} else {
			log.error("Message received is not a Map Message");
		}
	}

	private RepositoryBean createGitRepository(RuntimeBean runtimeBean, TenantInfoBean tenantInfoBean, String stage)
			throws AppFactoryException {
		RepositoryBean repositoryBean = new RepositoryBean();
		repositoryBean.setCommitEnabled(true);
		repositoryBean.setRepositoryType(AppFactoryConstants.REPOSITORY_TYPE_GIT);
		repositoryBean.setRepositoryAdminUsername(AppFactoryUtil.getAppfactoryConfiguration().getFirstProperty
				(AppFactoryConstants.PAAS_ARTIFACT_REPO_PROVIDER_ADMIN_USER_NAME));
		repositoryBean.setRepositoryAdminPassword(AppFactoryUtil.getAppfactoryConfiguration().getFirstProperty
				(AppFactoryConstants.PAAS_ARTIFACT_REPO_PROVIDER_ADMIN_PASSWORD));
		String repoUrl;
		try {
			String repoProviderClassName = AppFactoryUtil.getAppfactoryConfiguration().
					getFirstProperty(AppFactoryConstants.PAAS_ARTIFACT_REPO_PROVIDER_CLASS_NAME);
			ClassLoader loader = getClass().getClassLoader();
			Class<?> repoProviderClass = Class.forName(repoProviderClassName, true, loader);
			RepositoryProvider repoProvider = (RepositoryProvider) repoProviderClass.newInstance();
			repoProvider.setBaseUrl(AppFactoryUtil.getAppfactoryConfiguration().
					getFirstProperty(AppFactoryConstants.PAAS_ARTIFACT_REPO_PROVIDER_BASE_URL));
			repoProvider.setAdminUsername(repositoryBean.getRepositoryAdminUsername());
			repoProvider.setAdminPassword(repositoryBean.getRepositoryAdminPassword());
			repoProvider.setRepoName(generateRepoUrlFromTemplate(
					runtimeBean.getPaasRepositoryURLPattern(), tenantInfoBean.getTenantId(), stage));
			repoUrl = repoProvider.createRepository();
			repositoryBean.setRepositoryURL(repoUrl);
			log.info("Repo Url : " + repoUrl);
		} catch (InstantiationException e) {
			String msg = "Unable to create repository";
			throw new AppFactoryException(msg, e);
		} catch (IllegalAccessException e) {
			String msg = "Unable to create repository";
			throw new AppFactoryException(msg, e);
		} catch (AppFactoryException e) {
			String msg = "Unable to create repository";
			throw new AppFactoryException(msg, e);
		} catch (ClassNotFoundException e) {
			String msg = "PAAS artifact repository provider class not found";
			throw new AppFactoryException(msg, e);
		}
		return repositoryBean;
	}

	private String generateRepoUrlFromTemplate(String pattern, int tenantId,
	                                           String stage) {
		String s = pattern.replace(AppFactoryConstants.STAGE_PLACE_HOLDER, stage) + File.separator
		           + Integer.toString(tenantId);
		log.info("Generated repo URL for stage " + stage + " : " + s);
		return s;
	}

	private static SubscriptionInfo subscribe(RuntimeBean runtimeBean, TenantInfoBean tenantInfoBean,
	                                          RepositoryBean repositoryBean, ConfigurationContext configurationContext,
	                                          String stage) throws Exception {
		CartridgeSubscriptionManager cartridgeSubsciptionManager = new CartridgeSubscriptionManager();
		String appendStageToCartridgeInfo = AppFactoryUtil.getAppfactoryConfiguration().
				getFirstProperty(AppFactoryConstants.APPEND_STAGE_TO_CARTRIDGE_INFO);
		String tenantDomain = tenantInfoBean.getTenantDomain();
		String cartridgeType = runtimeBean.getCartridgeTypePrefix();
		if (Boolean.TRUE.equals(Boolean.parseBoolean(appendStageToCartridgeInfo))) {
			cartridgeType += stage.toLowerCase();
		}
		String subscriptionAlias;
		if (Boolean.TRUE.equals(Boolean.parseBoolean(appendStageToCartridgeInfo))) {
			subscriptionAlias = runtimeBean.getAliasPrefix() + stage.toLowerCase() +
			                    tenantDomain.replace(AppFactoryConstants.DOT_SEPERATOR,
			                                         AppFactoryConstants.SUBSCRIPTION_ALIAS_DOT_REPLACEMENT);
		} else {
			subscriptionAlias = runtimeBean.getAliasPrefix() +
			                    tenantDomain.replace(AppFactoryConstants.DOT_SEPERATOR,
			                                         AppFactoryConstants.SUBSCRIPTION_ALIAS_DOT_REPLACEMENT);
		}
		SubscriptionData subscriptionData = new SubscriptionData();
		subscriptionData.setCartridgeType(cartridgeType);
		subscriptionData.setCartridgeAlias(subscriptionAlias);
		subscriptionData.setAutoscalingPolicyName(runtimeBean.getAutoscalePolicy());
		subscriptionData.setDeploymentPolicyName(runtimeBean.getDeploymentPolicy());
		subscriptionData.setTenantDomain(tenantDomain);
		subscriptionData.setTenantId(ApplicationManagementUtil.getTenantId(configurationContext));
		subscriptionData.setTenantAdminUsername(tenantInfoBean.getAdmin());
		subscriptionData.setRepositoryType(repositoryBean.getRepositoryType());
		subscriptionData.setRepositoryURL(repositoryBean.getRepositoryURL());
		subscriptionData.setRepositoryUsername(repositoryBean.getRepositoryAdminUsername());
		subscriptionData.setRepositoryPassword(repositoryBean.getRepositoryAdminPassword());
		subscriptionData.setCommitsEnabled(repositoryBean.isCommitEnabled());
		return cartridgeSubsciptionManager.subscribeToCartridgeWithProperties(subscriptionData);
	}

	protected ConfigurationContext getConfigContext() {

		// If a tenant has been set, then try to get the ConfigurationContext of that tenant
		PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
		ConfigurationContextService configurationContextService =
				(ConfigurationContextService) carbonContext.getOSGiService(ConfigurationContextService.class);
		ConfigurationContext mainConfigContext = configurationContextService.getServerConfigContext();
		String domain = carbonContext.getTenantDomain();
		if (domain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(domain)) {
			return TenantAxisUtils.getTenantConfigurationContext(domain, mainConfigContext);
		} else if (carbonContext.getTenantId() == MultitenantConstants.SUPER_TENANT_ID) {
			return mainConfigContext;
		} else {
			throw new UnsupportedOperationException("Tenant domain unidentified. " +
			                                        "Upstream code needs to identify & set the tenant domain & tenant" +
			                                        " ID. " +
			                                        " The TenantDomain SOAP header could be set by the clients or " +
			                                        "tenant authentication should be carried out.");
		}
	}

	/**
	 * super admin adds a tenant
	 *
	 * @param tenantInfoBean tenant info bean
	 * @return UUID
	 * @throws Exception if error in adding new tenant.
	 */
	public String addTenant(TenantInfoBean tenantInfoBean) throws Exception {
		try {
			PrivilegedCarbonContext.startTenantFlow();
			PrivilegedCarbonContext.getThreadLocalCarbonContext()
			                       .setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
			PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(MultitenantConstants.SUPER_TENANT_ID);
			Tenant tenant = TenantMgtUtil.initializeTenant(tenantInfoBean);
			TenantPersistor persistor = new TenantPersistor();
			// not validating the domain ownership, since created by super tenant
			int tenantId = persistor.persistTenant(tenant, false, tenantInfoBean.getSuccessKey(),
			                                       tenantInfoBean.getOriginatedService(), false);
			tenantInfoBean.setTenantId(tenantId);
			TenantMgtUtil.addClaimsToUserStoreManager(tenant);
			//Notify tenant addition
			try {
				TenantMgtUtil.triggerAddTenant(tenantInfoBean);
			} catch (StratosException e) {
				String msg = "Error in notifying tenant addition.";
				log.error(msg, e);
				throw new Exception(msg, e);
			}
			TenantMgtUtil.activateTenantInitially(tenantInfoBean, tenantId);
			return TenantMgtUtil.prepareStringToShowThemeMgtPage(tenant.getId());
		} finally {
			PrivilegedCarbonContext.endTenantFlow();
		}
	}
}