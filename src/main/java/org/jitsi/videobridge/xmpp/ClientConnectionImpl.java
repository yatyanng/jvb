/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.xmpp;

import java.util.Collection;

import org.jitsi.osgi.ServiceUtils2;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.xmpp.mucclient.IQListener;
import org.jitsi.xmpp.mucclient.MucClient;
import org.jitsi.xmpp.mucclient.MucClientConfiguration;
import org.jitsi.xmpp.mucclient.MucClientManager;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.IQ;
import org.json.simple.JSONObject;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.ColibriConferenceIQ;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.ShutdownIQ;
import net.java.sip.communicator.impl.protocol.jabber.extensions.health.HealthCheckIQ;
import net.java.sip.communicator.util.Logger;
import net.java.sip.communicator.util.ServiceUtils;

/**
 * Provides jitsi-videobridge functions through an XMPP client connection.
 *
 * @author Boris Grozev
 */
public class ClientConnectionImpl implements IQListener, BundleActivator {
	/**
	 * The {@link Logger} used by the {@link ClientConnectionImpl} class and its
	 * instances for logging output.
	 */
	private static final org.jitsi.util.Logger logger = org.jitsi.util.Logger.getLogger(ClientConnectionImpl.class);

	/**
	 * The prefix of the property names used to configure this bundle.
	 */
	private static final String PREFIX = "org.jitsi.videobridge.xmpp.user.";

	/**
	 * The {@link MucClientManager} which manages the XMPP user connections and the
	 * MUCs.
	 */
	private MucClientManager mucClientManager;

	/**
	 * The {@link XmppCommon} instance which implements handling of Smack IQs for
	 * this {@link ClientConnectionImpl}.
	 */
	private final XmppCommon common = new XmppCommon();

	/**
	 * Starts this bundle.
	 */
	@Override
	public void start(BundleContext bundleContext) {
		ConfigurationService config = ServiceUtils.getService(bundleContext, ConfigurationService.class);
		if (config == null) {
			logger.info("Not using XMPP user login; no config service.");
			return;
		}

		// Register this instance as an OSGi service.
		Collection<ClientConnectionImpl> userLoginBundles = ServiceUtils2.getServices(bundleContext,
				ClientConnectionImpl.class);

		if (!userLoginBundles.contains(this)) {
			common.start(bundleContext);

			mucClientManager = new MucClientManager(XmppCommon.FEATURES);

			// These are the IQs that we are interested in.
			mucClientManager.registerIQ(new HealthCheckIQ());
			mucClientManager.registerIQ(new ColibriConferenceIQ());
			mucClientManager.registerIQ(new org.jivesoftware.smackx.iqversion.packet.Version());
			mucClientManager.registerIQ(ShutdownIQ.createForceShutdownIQ());
			mucClientManager.registerIQ(ShutdownIQ.createGracefulShutdownIQ());
			mucClientManager.setIQListener(this);

			Collection<MucClientConfiguration> configurations = MucClientConfiguration.loadFromConfigService(config,
					PREFIX, true);
			configurations.forEach(c -> mucClientManager.addMucClient(c));

			bundleContext.registerService(ClientConnectionImpl.class, this, null);
		} else {
			logger.error("Already started");
		}
	}

	/**
	 * Processes an {@link IQ} received by one of the {@link MucClient}s of
	 * {@link #mucClientManager}.
	 *
	 * @param iq the IQ to process.
	 * @return the IQ to send as a response, or {@code null}.
	 */
	@Override
	public IQ handleIq(IQ iq) {
		return common.handleIQ(iq);
	}

	/**
	 * Stops this bundle.
	 */
	@Override
	public void stop(BundleContext bundleContext) throws Exception {
		try {
			// Unregister this instance as an OSGi service.
			Collection<ServiceReference<ComponentImpl>> serviceReferences = bundleContext
					.getServiceReferences(ComponentImpl.class, null);

			if (serviceReferences != null) {
				for (ServiceReference<ComponentImpl> serviceReference : serviceReferences) {
					Object service = bundleContext.getService(serviceReference);

					if (service == this)
						bundleContext.ungetService(serviceReference);
				}
			}
		} finally {
			common.stop(bundleContext);
		}
	}

	/**
	 * Adds an {@link ExtensionElement} to our presence, and removes any other
	 * extensions with the same element name and namespace, if any exists.
	 * 
	 * @param extension the extension to add.
	 */
	public void setPresenceExtension(ExtensionElement extension) {
		mucClientManager.setPresenceExtension(extension);
	}

	/**
	 * Adds a new {@link MucClient} with configuration described in JSON.
	 * 
	 * @param jsonObject the JSON which describes the configuration of the client.
	 *                   <p/>
	 * 
	 *                   <pre>
	 * {@code
	 * The expected JSON format is:
	 * {
	 *     "id": "muc-client-id",
	 *     "key": "value"
	 * }
	 * }
	 *                   </pre>
	 * 
	 *                   The [key, value] pairs are interpreted as property names
	 *                   and values to set for the client's configuration (see
	 *                   {@link MucClientConfiguration}).
	 *
	 * @return {@code true} if the request was successful (i.e. the JSON is in the
	 *         required format and either a new {@link MucClient} was added or a
	 *         client with the same ID already existed).
	 */
	public boolean addMucClient(JSONObject jsonObject) {
		if (jsonObject == null || !(jsonObject.get("id") instanceof String)) {
			return false;
		}
		MucClientConfiguration config = new MucClientConfiguration((String) jsonObject.get("id"));

		for (Object key : jsonObject.keySet()) {
			Object value = jsonObject.get(key);
			if (key instanceof String && value instanceof String && !"id".equals(key)) {
				config.setProperty((String) key, (String) value);
			}
		}

		if (!config.isComplete()) {
			logger.info("Not adding a MucClient, configuration incomplete.");
			return false;
		} else {
			if (mucClientManager == null) {
				logger.warn("Not adding a MucClient. Not started?");
				return false;
			}
			mucClientManager.addMucClient(config);

			// We consider the case where a client with the given ID already
			// exists as success. Note however, that the existing client's
			// configuration was NOT modified.
			return true;
		}
	}

	/**
	 * Removes a {@link MucClient} with an ID described in JSON.
	 * 
	 * @param jsonObject the JSON which contains the ID of the client to remove.
	 *                   </p>
	 * 
	 *                   <pre>
	 * {@code
	 * The expected JSON format is:
	 * {
	 *     "id": "muc-client-id",
	 * }
	 * }
	 *                   </pre>
	 *
	 * @return {@code false} if this instance has not been initialized, or the JSON
	 *         is not in the expected format. Otherwise (regardless of whether a
	 *         client with the specified ID existed or not), returns {@code true}.
	 */
	public boolean removeMucClient(JSONObject jsonObject) {
		if (jsonObject == null || !(jsonObject.get("id") instanceof String) || mucClientManager == null) {
			return false;
		}

		mucClientManager.removeMucClient((String) jsonObject.get("id"));
		return true;
	}
}
