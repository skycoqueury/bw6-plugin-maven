package com.tibco.bw.maven.plugin.admin.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status.Family;
import javax.ws.rs.core.UriBuilder;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.logging.Log;
import org.glassfish.jersey.SslConfigurator;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.multipart.Boundary;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.FileDataBodyPart;

import com.tibco.bw.maven.plugin.admin.dto.Agent;
import com.tibco.bw.maven.plugin.admin.dto.AppInstance;
import com.tibco.bw.maven.plugin.admin.dto.AppNode;
import com.tibco.bw.maven.plugin.admin.dto.AppSpace;
import com.tibco.bw.maven.plugin.admin.dto.Application;
import com.tibco.bw.maven.plugin.admin.dto.Archive;
import com.tibco.bw.maven.plugin.admin.dto.Domain;
import com.tibco.bw.maven.plugin.utils.Constants;

public class RemoteDeployer {
	private static final String DATE_TIME = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
	private static final String CONTEXT_ROOT = "/bw/v1";
	private Client jerseyClient;
	private WebTarget r;
	private String scheme = "http";
	private final String host;
	private final int port;
	private final String agentAuth;
	private final boolean agentSSL;
	private final String username;
	private final String password;
	private final String trustPath;
	private final String trustPassword;
	private final String keyPath;
	private final String keyPassword;
	private final boolean createAdminCompo;
	private final int connectTimeout;
	private final int readTimeout;
	private final int retryCount;
	private Log log;
	private int SLEEP_INTERVAL = 10000;
	private final boolean startOndeploy;

	public RemoteDeployer(final String host, final int port, final String agentAuthType, final String username, final String password, final boolean agentSSL, final String trustFilePath, final String trustPassword, final String keyFilePath, final String keyPassword, final boolean createAdminCompo, final int connectTimeout, final int readTimeout, final int retryCount, final boolean startOndeploy) {
		this.host = host;
		this.port = port;
		this.agentAuth = agentAuthType;
		this.username = username;
		this.password = password;
		this.agentSSL = agentSSL;
		this.trustPath = trustFilePath;
		this.trustPassword = trustPassword;
		this.keyPath = keyFilePath;
		this.keyPassword = keyPassword;
		this.createAdminCompo = createAdminCompo;
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.retryCount = retryCount;
		this.startOndeploy = startOndeploy;
	}

	private void init() {
		if (this.jerseyClient == null) {
			ClientConfig clientConfig = new ClientConfig();
			clientConfig = clientConfig.property(ClientProperties.CONNECT_TIMEOUT, connectTimeout);
			clientConfig = clientConfig.property(ClientProperties.READ_TIMEOUT, readTimeout);
			clientConfig.register(JacksonFeature.class).register(MultiPartFeature.class);
			// Configuration for SSL enabled BWAgent
			if(agentSSL) {
				scheme = "https";
				SslConfigurator sslConfig;
				if(keyPath != null && !keyPath.isEmpty() && keyPassword != null && !keyPassword.isEmpty()) {
					sslConfig = SslConfigurator.newInstance()
					        .trustStoreFile(trustPath)
					        .trustStorePassword(trustPassword)
					        .keyStoreFile(keyPath)
					        .keyPassword(keyPassword);
				} else {
					sslConfig = SslConfigurator.newInstance()
					        .trustStoreFile(trustPath)
					        .trustStorePassword(trustPassword);
				}
				this.jerseyClient = ClientBuilder.newBuilder()
						.withConfig(clientConfig)
						.sslContext(sslConfig.createSSLContext())
						.hostnameVerifier(new HostnameVerifier() {
						    @Override
						    public boolean verify(String hostname, SSLSession session) {
						        return true;
						    }
						})
						.build();
			} else {
				this.jerseyClient = ClientBuilder.newClient(clientConfig);
			}
			// Configuration for Authentication enabled BWAgent (BASIC / DIGEST)
			if(agentAuth != null && Constants.BASIC_AUTH.equalsIgnoreCase(agentAuth)) {
				HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(username, password);
				this.jerseyClient.register(feature);
			} else if(agentAuth != null && Constants.DIGEST_AUTH.equalsIgnoreCase(agentAuth)) {
				HttpAuthenticationFeature feature = HttpAuthenticationFeature.digest(username, password);
				this.jerseyClient.register(feature);
			}
		}
		this.r = this.jerseyClient.target(UriBuilder.fromPath(CONTEXT_ROOT).scheme(this.scheme).host(this.host).port(this.port).build());
	}

	public void setLog(Log log) {
		this.log = log;
	}

	public void close() {
		if (this.jerseyClient != null) {
			this.jerseyClient.close();
			this.jerseyClient = null;
		}
	}

	public List<Agent> getAgentInfo() throws ClientException {
		init();
		try {
			Response response = r.path("/agents").path("info").request(MediaType.APPLICATION_JSON_TYPE).get();
			processErrorResponse(response);
			List<Agent> info = response.readEntity(new GenericType<List<Agent>>() {});
			return info;
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	public Domain getOrCreateDomain(final String name, final String desc) throws ClientException {
		List <Domain> domains = getDomains(null, false, true);
		for(Domain domain : domains) {
			if(domain.getName().equals(name)) {
				log.info("Domain exists with Name -> " + name);
				return domain;
			}
		}
		if(!createAdminCompo){
			throw new ClientException("Domain does not exist with Name -> " +name);
		}
		log.info("Creating Domain with name -> " + name);
		return createDomain(name, desc, "owner", null, null);
	}

	private Domain createDomain(final String name, final String description, final String owner, final String agent, final String home) throws ClientException {
		init();
		try {
			addQueryParam("desc", description);
			addQueryParam("agent", agent);
			addQueryParam("owner", owner);
			addQueryParam("home", home);
			Response response = r.path("/domains").path(name).request(MediaType.APPLICATION_JSON_TYPE).post(null);
			processErrorResponse(response);
			Domain domain = response.readEntity(Domain.class);
			return domain;
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	private List<Domain> getDomains(final String filter, final boolean full, final boolean status) throws ClientException {
		init();
		try {
			r = r.queryParam("full", full).queryParam("status", status);
			addQueryParam("filter", filter);
			Response response = r.path("/browse").path("domains").request(MediaType.APPLICATION_JSON_TYPE).get();
			processErrorResponse(response);
			List<Domain> domains = response.readEntity(new GenericType<List<Domain>>() {});
			return domains;
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	private boolean isValidURL(String url){
	  try { 
	       new URL(url).toURI(); 
	       return true; 
	  } catch (Exception e) { 
	       return false; 
	  } 
	}
	
	private AppSpace setProfile(final String domainName, final String appSpaceName, final String version,final String appName,final String externalProfileLoc) throws ClientException {
		init();
		log.info("Setting external profile for AppName -> "+ appName + ", Version -> "+ version + ", External profile location -> "+ externalProfileLoc);
		File profilefile = null;
		boolean fromURL = false;
		try {
			if(externalProfileLoc.contains("http") && isValidURL(externalProfileLoc)){
				String localFileName = externalProfileLoc.substring(externalProfileLoc.lastIndexOf("/")+1);
				log.debug("Profile file is from external URL, creating temporary local file - "+ localFileName);
				profilefile = new File(localFileName);
				profilefile.delete();
				profilefile.createNewFile();
				FileUtils.copyURLToFile(new URL(externalProfileLoc), profilefile);
				fromURL = true;
			} else {
				profilefile = new File(externalProfileLoc);
				if(!profilefile.exists()){
					throw new Exception("External profile file not found - "+ externalProfileLoc);
				}
			}
			final FileDataBodyPart filePart = new FileDataBodyPart("file", profilefile);
			FormDataMultiPart formDataMultiPart  = new FormDataMultiPart();
		    final FormDataMultiPart multipart = (FormDataMultiPart) formDataMultiPart.bodyPart(filePart);
			  MediaType contentType = formDataMultiPart .getMediaType();
			Response response = r.path("/domains").path(domainName).path("appspaces").path(appSpaceName).path("applications").path(appName).path(version).path("config").path("externalprofile").request().put(Entity.entity(multipart , contentType));
			processErrorResponse(response);
			AppSpace appSpace = response.readEntity(AppSpace.class);
			
			if(fromURL){
				profilefile.delete();
				log.debug("Removed temporary file - "+ profilefile.getName());
			}
			
			return appSpace;
			
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			if(fromURL && profilefile != null){
				profilefile.delete();
				log.debug("Removed temporary file - "+ profilefile.getName());
			}
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}
	public AppSpace getOrCreateAppSpace(final String domainName, final String appSpaceName, final String desc) throws ClientException {
		List<AppSpace> appSpaces = getAppSpaces(domainName, null, false, true);
		for(AppSpace appSpace : appSpaces) {
			if(appSpace.getName().equals(appSpaceName)) {
				log.info("AppSpace exists with Name -> " + appSpaceName + " in Domain -> " + domainName);
				return appSpace;
			}
		}
		if(!createAdminCompo){
			throw new ClientException("AppSpace does not exist with Name -> " +appSpaceName);
		}
		
		log.info("Creating AppSpace with Name -> " + appSpaceName + " in Domain -> "  + domainName);
		return createAppSpace(domainName, appSpaceName, true, 0, null, desc, "owner");
	}

	public AppNode getOrCreateAppNode(final String domainName, final String appSpaceName, final String appNodeName, final int httpPort, final int osgiPort, final String description, final String agentName) throws ClientException {		
		List<AppNode> nodes = getAppNodes(domainName, appSpaceName, null, true);
		for(AppNode node : nodes) {
			if(node.getName().equals(appNodeName)) {
				log.info("AppNode exists with Name -> " + appNodeName + " in Domain -> " + domainName  + " and in AppSpace -> " + appSpaceName);
				log.info("AppNode HTTP Port  -> " + httpPort + ". AppNode OSGi Port -> " + osgiPort);
				return node;
			}
		}
		if(!createAdminCompo){
			throw new ClientException("AppNode does not exist with Name -> " +appNodeName);
		}
		log.info("Creating AppNode with Name -> " +  appNodeName + " in Domain -> " + domainName  + " and in AppSpace -> " + appSpaceName);
		return createAppNode(domainName, appSpaceName, appNodeName, agentName, httpPort, osgiPort, description);
	}

	public void addAndDeployApplication(final String domainName, final String appSpaceName, final String appName, final String earName, 
										final String file, final boolean replace, final String profile, final boolean backupEar, 
										final String backupLocation,final String version,final boolean externalProfile, 
										final String externalProfileLoc, final String appNodeName, final String path, boolean skipUploadArchive) throws Exception {
		List<Application> applications = getApplications(domainName, appSpaceName, null, true);
		String appDescription=null;
		
		for(Application application : applications) {
			//= application.getDescription();
			//log.info("*********"+appDescription+"**********");
				if(application.getName().equals(appName)) {
					if(replace) {
						// Backup ear and profile
						if(backupEar) {
							String earPath = (path == null || path.isEmpty() ? "" : path+":") + application.getArchiveName().toString();
							log.info("Generating backup ear file for application -> " + appName);
							downloadArchive(domainName, backupLocation, earPath);
							if(externalProfile) {
								log.info("Skipping backup for external profile");
							} else {
								log.info("Generating backup substvar file for profile -> " + application.getProfileName());
								downloadProfileAplication(domainName, backupLocation, earPath, application.getProfileName());
							}
						}
						log.info("Application exists with name -> " + appName + ". Undeploying the Application as Redeploy flag is true.");
						undeployApplication(domainName, appSpaceName, appName, application.getVersion());	
					} else {
						log.info("Application exists with name -> " + appName + ". Not Re-deploying the Application as Redeploy flag is false.");
						return;
					}
				}
			}
		
		 	if (!skipUploadArchive) {
				log.info("Uploading the Archive file -> " + earName + ", EAR Upload Path -> "+ path);
				uploadArchive(domainName, path, file, true);
		 	}
		 	else {
				log.info("Skipping - Uploading the Archive file -> " + earName + ", EAR Upload Path -> "+ path);		 		
		 	}
			log.info("Deploying the Application with name -> " + appName + " with Profile -> " + profile);
			deployApplication(domainName, appSpaceName, earName, path, startOndeploy, replace, profile,externalProfile,appDescription);
			// fix from runtime side only
			// UI doesn't seem to fix the externalProfile flag when profile is set to an application Profile 
			// We should use the external profile here if this is really the case
			if(externalProfile && !externalProfileLoc.isEmpty() && (profile != null && profile.equals("other")) ) {
				setProfile(domainName,appSpaceName,version,appName,externalProfileLoc);
				log.info("Starting Application -> "+ appName);
				if(startOndeploy){
					startApplication(domainName,appSpaceName,appName,version,appNodeName, true);
				}
			}
			Thread.sleep(SLEEP_INTERVAL);
			if(startOndeploy){
				checkApplicationState(domainName, appSpaceName, appName, version, Application.ApplicationRuntimeStates.Running);
			}
			else{
				log.info("AppName -> "+ appName + " will not auto start since startOnDeploy flag is -> "+ startOndeploy);
			}
	}

	private List<AppSpace> getAppSpaces(final String domainName, final String filter, final boolean full, final boolean status) throws ClientException {
		init();
		try {
			addQueryParam("domain", domainName);
			r = r.queryParam("full", full).queryParam("status", status);
			addQueryParam("filter", filter);
			Response response = r.path("/browse").path("appspaces").request(MediaType.APPLICATION_JSON_TYPE).get();
			processErrorResponse(response);
			List<AppSpace> appSpaces = response.readEntity(new GenericType<List<AppSpace>>() {});
			return appSpaces;
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	private AppSpace createAppSpace(final String domainName, final String appSpaceName, final boolean elastic, final int minNodes, final String version, final String description, final String owner) throws ClientException {
		init();
		try {
			r = r.queryParam("elastic", String.valueOf(elastic)).queryParam("minNodes", String.valueOf(minNodes));
			addQueryParam("version", version);
			addQueryParam("desc", description);
			addQueryParam("owner", owner);
			Response response = r.path("/domains").path(domainName).path("appspaces").path(appSpaceName).request(MediaType.APPLICATION_JSON_TYPE).post(null);
			processErrorResponse(response);
			AppSpace appSpace = response.readEntity(AppSpace.class);
			return appSpace;
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	public void startAppSpace(final String domainName, final String appSpaceName) throws ClientException {
		init();
		log.info("Starting AppSpace with name -> " + appSpaceName + " in Domain -> " + domainName);
		try {
			Response response = r.path("/domains").path(domainName).path("appspaces").path(appSpaceName).path("start").request(MediaType.APPLICATION_JSON_TYPE).post(null);
			//retry and check appspace status if appspace is not running.
			if (!Family.SUCCESSFUL.equals(response.getStatusInfo().getFamily())) {
				boolean isRunning = false;
				int count = 0;
				log.debug("Retry Count ->"+ retryCount);
				while(!isRunning && count < retryCount ){
					Response resp = r.path("/domains").path(domainName).path("appspaces").path(appSpaceName).queryParam("status","true").queryParam("full","false").request(MediaType.APPLICATION_JSON_TYPE).get();
					processErrorResponse(resp);
					AppSpace appSpace = resp.readEntity(AppSpace.class);
					log.info("AppSpace -> "+ appSpaceName + ", Status -> "+ appSpace.getStatus());
					if(appSpace.getStatus().equals(AppSpace.AppSpaceRuntimeStatus.Running))
					{
						isRunning = true;
						break;
					}
					count++;
					Thread.sleep(SLEEP_INTERVAL);
				}
				if(!isRunning){
					processErrorResponse(response);
				}
			}
			
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	private AppNode createAppNode(final String domainName, final String appSpaceName, final String appNodeName, final String agentName, final int httpPort, final int osgiPort, final String description) throws ClientException {
		init();
		try {
			addQueryParam("agent", String.valueOf(agentName));
			addQueryParam("httpport", String.valueOf(httpPort));
			if (osgiPort > 0) {
				r = r.queryParam("osgiport", String.valueOf(osgiPort));
			}
			addQueryParam("description", description);
			Response response = r.path("/domains").path(domainName).path("appspaces").path(appSpaceName).path("appnodes").path(appNodeName).request(MediaType.APPLICATION_JSON_TYPE).post(null);
			processErrorResponse(response);
			AppNode appNode = response.readEntity(AppNode.class);
			return appNode;
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	@SuppressWarnings("unused")
	private void startAppNode(final String domainName, final String appSpaceName, final String appNodeName) throws ClientException {
		init();
		try {
			Response response = r.path("/domains").path(domainName).path("appspaces").path(appSpaceName).path("appnodes").path(appNodeName).path("start").request(MediaType.APPLICATION_JSON_TYPE).post(null);
			processErrorResponse(response);
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}
	
	private void removeArchive(final String domainName, final String path, final String file) throws ClientException {
		init();
		String earPath = (path.isEmpty() ? "" : path+":") + file;
		log.debug("Removing archive at path -> "+ earPath + ", Domain Name -> "+ domainName);
		try {
			r = r.path("/domains").path(domainName).path("archives").path(earPath);
			Response response = r.request(MediaType.APPLICATION_JSON_TYPE).delete();
			if (!response.getStatusInfo().getFamily().equals(Family.SUCCESSFUL)) {
				if (response.getMediaType().getType().equals(MediaType.TEXT_HTML_TYPE.getType()) && response.getMediaType().getSubtype().equals(MediaType.TEXT_HTML_TYPE.getSubtype())) {
					throw new ClientException(response.getStatus(), response.readEntity(String.class), null);
				} else {
					processErrorResponse(response);
				}
			}
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	private void uploadArchive(final String domainName, final String path, final String file, final boolean replace) throws ClientException {
		init();
		String earPath = (path == null || path.isEmpty())? null : path;
		try (MultiPart multipart = new FormDataMultiPart()) {
			r = r.path("/domains").path(domainName).path("archives");
			r = r.queryParam("replace", replace);
			addQueryParam("path", earPath);

			File fileEntity = new File(file);
			final FileDataBodyPart filePart = new FileDataBodyPart("file", fileEntity, MediaType.APPLICATION_OCTET_STREAM_TYPE);

			FormDataContentDisposition.FormDataContentDispositionBuilder builder = FormDataContentDisposition.name("file");
			builder.fileName(URLEncoder.encode(file, "UTF-8"));
			builder.size(fileEntity.length());
			builder.modificationDate(new Date(fileEntity.lastModified()));
			filePart.setFormDataContentDisposition(builder.build());
			multipart.bodyPart(filePart);

			Response response = r.request(MediaType.APPLICATION_JSON_TYPE).post(Entity.entity(multipart, multipart.getMediaType()));

			if (!response.getStatusInfo().getFamily().equals(Family.SUCCESSFUL)) {
				if (response.getMediaType().getType().equals(MediaType.TEXT_HTML_TYPE.getType()) && response.getMediaType().getSubtype().equals(MediaType.TEXT_HTML_TYPE.getSubtype())) {
					throw new ClientException(response.getStatus(), response.readEntity(String.class), null);
				} else {
					processErrorResponse(response);
				}
			}
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	private void deployApplication(final String domainName, final String appSpaceName, final String archiveName, final String path, final boolean startOnDeploy, final boolean replace, final String profile, boolean externalProfile,final String appDescription) throws ClientException {
		init();
		try {
			r = r.queryParam("archivename", archiveName);
			addQueryParam("path", path);
			
			
			
			if(externalProfile){
				r = r.queryParam("startondeploy", "false").queryParam("replace", String.valueOf(replace));
				addQueryParam("profile", "default.substvar");
			}
			else{
				r = r.queryParam("startondeploy", String.valueOf(startOnDeploy)).queryParam("replace", String.valueOf(replace));
				addQueryParam("profile", profile);
			}
			//addQueryParam("description",appDescription);
			//log.info("*********"+appDescription+"**********");
			Response response = r.path("/domains").path(domainName).path("appspaces").path(appSpaceName).path("applications").request(MediaType.APPLICATION_JSON_TYPE).post(null);
			processErrorResponse(response);
		//	Application application = response.readEntity(Application.class);
		//Rest API response has been changed. Now it returns 201 in case of successful deployment.
			if(response.getStatus() != 201 ) {
				throw new ClientException(500, response.getStatus() + ": " , null);
			}
			
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	private void undeployApplication(final String domainName, final String appSpaceName, final String appName, final String version) throws ClientException {
		init();
		try {
			Response response = r.path("/domains").path(domainName).path("appspaces").path(appSpaceName).path("applications").path(appName).path(version).request(MediaType.APPLICATION_JSON_TYPE).delete();
			processErrorResponse(response);
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	private void startApplication(final String domainName, final String appSpaceName, final String appName, final String version, final String appNodeName, final boolean startOnAllAppNodes) throws ClientException {
		init();
		try {
			if(!startOnAllAppNodes)
				addQueryParam("appnode", appNodeName);
			Response response = r.path("/domains").path(domainName).path("appspaces").path(appSpaceName).path("applications").path(appName).path(version).path("start").request(MediaType.APPLICATION_JSON_TYPE).post(null);
			processErrorResponse(response);
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}
	
	private void stopApplication(final String domainName, final String appSpaceName, final String appName, final String version, final String appNodeName, final boolean stopOnAllAppnodes) throws ClientException {
		init();
		try {
			if(!stopOnAllAppnodes)
				addQueryParam("appnode", appNodeName);
			Response response = r.path("/domains").path(domainName).path("appspaces").path(appSpaceName).path("applications").path(appName).path(version).path("stop").request(MediaType.APPLICATION_JSON_TYPE).post(null);
			processErrorResponse(response);
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}
	
	private void checkApplicationState(final String domainName, final String appSpaceName, final String appName, final String version, final Application.ApplicationRuntimeStates state) throws ClientException, InterruptedException{
		int count = 0;
		boolean isState= false;
		log.debug("Retry Count ->"+ retryCount);
		while(!isState && count < retryCount ){
			Response response = r.path("/domains").path(domainName).path("appspaces").path(appSpaceName).path("applications").path(appName).path(version).request(MediaType.APPLICATION_JSON_TYPE).get();
			processErrorResponse(response);
			Application app = response.readEntity(Application.class);
			log.info("AppName -> "+ appName + ", State -> "+ app.getState());
			if(app.getState().equals(state)){
				isState = true;
				return;
			}
			count++;
			Thread.sleep(SLEEP_INTERVAL);
		}
	}
	
	private void checkAppInstanceState(final String domainName, final String appSpaceName, final String appName, final String version, final Application.ApplicationRuntimeStates state, final String appNode) throws Exception{
		int count = 0;
		boolean isState= false;
		log.debug("Retry Count ->"+ retryCount);
		while(!isState && count < retryCount ){
			Response response = r.path("/domains").path(domainName).path("appspaces").path(appSpaceName).path("applications").path(appName).path(version).request(MediaType.APPLICATION_JSON_TYPE).get();
			processErrorResponse(response);
			Application app = response.readEntity(Application.class);
			AppInstance appInst = null;
			for(AppInstance appInstance : app.getInstances()){
				if(appInstance.getAppNodeName().equalsIgnoreCase(appNode))
				{
					appInst = appInstance;
					break;
				}
			}
			log.info("AppName -> "+ appName + ", AppNode -> "+ appNode + ", State -> "+ appInst.getState());
			if(appInst.getState().equals(state.toString())){
				isState = true;
				return;
			}
			count++;
			Thread.sleep(SLEEP_INTERVAL);
		}
		throw new Exception("Failed application instance status check, expected state -> "+ state.toString()+". Please adjust retry count if application takes longer time.");
	}

	private List<AppNode> getAppNodes(final String domainName, final String appSpaceName, final String filter, final boolean status) throws ClientException {
		init();
		try {
			r = r.queryParam("domain", domainName).queryParam("appspace", appSpaceName).queryParam("status", status);
			addQueryParam("filter", filter);
			Response response = r.path("/browse").path("appnodes").request(MediaType.APPLICATION_JSON_TYPE).get();
			processErrorResponse(response);
			List<AppNode> appSpaces = response.readEntity(new GenericType<List<AppNode>>() {});
			return appSpaces;
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	@SuppressWarnings("unused")
	private List<Archive> getArchives(final String domainName, final String path, final String filter) throws ClientException {
		init();
		try {
			r = r.queryParam("domain", domainName);
			addQueryParam("path", path);
			addQueryParam("filter", filter);
			Response response = r.path("/browse").path("archives").request(MediaType.APPLICATION_JSON_TYPE).get();
			processErrorResponse(response);
			List<Archive> archives = response.readEntity(new GenericType<List<Archive>>() {});
			return archives;
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	private List<Application> getApplications(final String domainName, final String appSpace, final String filter, final boolean status) throws ClientException {
		init();
		try {
			addQueryParam("domain", domainName);
			addQueryParam("appspace", appSpace);
			addQueryParam("filter", filter);
			r = r.queryParam("status", status);
			Response response = r.path("/browse").path("apps").request(MediaType.APPLICATION_JSON_TYPE).get();
			processErrorResponse(response);
			List<Application> apps = response.readEntity(new GenericType<List<Application>>() {});
			return apps;
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	private void downloadArchive(final String domainName, final String path, final String name) throws ClientException {
		init();
		try {
			Response response = r.path("/domains").path(domainName).path("archives").path(name).path("content").request().get();
			processErrorResponse(response);
			saveArchive(response, path, name.replace(":", "_"));
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	private void downloadProfileAplication(final String domainName, final String path, final String name, final String profileName) throws ClientException {
		init();
		try {
			Response response = r.path("/domains").path(domainName).path("archives").path(name).path(profileName).request().get();
			processErrorResponse(response);
			saveArchive(response, path, profileName);
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}

	private void saveArchive(Response response, final String path, final String name) throws IOException {
		FileOutputStream outputStream = null;
		try {
			// Read response string
			InputStream inputStream = response.readEntity(InputStream.class);
			String fullPath = path + File.separator + DATE_TIME;
			File file = new File(fullPath);
			FileUtils.forceMkdir(file);
			//parentPath = file.getAbsoluteFile().getAbsolutePath();
			fullPath += File.separator + name;
			outputStream = new FileOutputStream(fullPath);
			byte[] buffer = new byte[1024];
			int bytesRead;
			while ((bytesRead = inputStream.read(buffer)) != -1) {
				outputStream.write(buffer, 0, bytesRead);
			}
			log.info("Downloaded successfully at: " + fullPath);
		} catch (IOException e) {
			throw e;
		} finally {
			if(outputStream != null) {
				try {
					outputStream.close();
				} catch (IOException e) {
					log.error(e);
				}
			}
		}
	}

	private void processErrorResponse(Response response) throws ClientException {
		if (!Family.SUCCESSFUL.equals(response.getStatusInfo().getFamily())) {
			if(response.getStatusInfo().getStatusCode() == 401) {
				throw new ClientException(response.getStatus(),  response.getStatusInfo().getStatusCode() + ": " + response.getStatusInfo().getReasonPhrase(), null);
			}
			com.tibco.bw.maven.plugin.admin.dto.Error error = response.readEntity(com.tibco.bw.maven.plugin.admin.dto.Error.class);
			if (error != null) {
				throw new ClientException(response.getStatus(), error.getCode() + ": " + error.getMessage(), null);
			} else {
				throw new ClientException(response.getStatus(), response.getStatusInfo().getReasonPhrase(), null);
			}
		}
	}

	private static ClientException getConnectionException(ProcessingException pe) {
		if (pe.getCause() instanceof ConnectException) {
			return new ClientException(503, pe.getCause().getMessage(), pe.getCause());
		}
		//https://java.net/jira/browse/JERSEY-2728
		if (pe.getCause() instanceof IllegalStateException) {
			return new ClientException(503, pe.getCause().getMessage(), pe.getCause());
		}
		return new ClientException(500, pe.getMessage(), pe);
	}

	private void addQueryParam(final String name, final String value) {
		if(value != null) {
			r = r.queryParam(name, value);
		}
	}

	public void setAppNodeConfig(String domain, String appSpace,
			String appNode, Map<String,String> appNodeConfig, boolean restartAppNode) throws Exception {
		init();
		try {
			//update config
			log.info("Updating AppNode Config ("+ appNode+") -> "+ appNodeConfig);
			Response responseUpdate = r.path("/domains").path(domain).path("appspaces").path(appSpace).path("appnodes").path(appNode).path("/config").request(MediaType.APPLICATION_JSON_TYPE).put(Entity.entity(appNodeConfig, MediaType.APPLICATION_JSON));
			processErrorResponse(responseUpdate);
			
			//restart appnode
			if(restartAppNode)
			{
				//stop appnode
				log.info("Stopping AppNode ("+ appNode+")");
				Response responseStop = r.path("/domains").path(domain).path("appspaces").path(appSpace).path("appnodes").path(appNode).path("/stop").request(MediaType.APPLICATION_JSON_TYPE).post(null);
				processErrorResponse(responseStop);
				
				//start appnode
				log.info("Starting AppNode ("+ appNode+")");
				Response responseStart = r.path("/domains").path(domain).path("appspaces").path(appSpace).path("appnodes").path(appNode).path("/start").request(MediaType.APPLICATION_JSON_TYPE).post(null);
				processErrorResponse(responseStart);
				
			} else {
				log.info("Restart AppNode flag is false. The AppNode state will be Out-of-sync.");
			}
		} catch (ProcessingException pe) {
			throw getConnectionException(pe);
		} catch (Exception ex) {
			throw new ClientException(500, ex.getMessage(), ex);
		}
	}
	
}
