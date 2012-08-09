/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
package org.zaproxy.zap.spider;

import java.io.IOException;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.List;

import net.htmlparser.jericho.Source;

import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.URI;
import org.apache.log4j.Logger;
import org.parosproxy.paros.model.HistoryReference;
import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpMalformedHeaderException;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.zaproxy.zap.spider.filters.ParseFilter;
import org.zaproxy.zap.spider.parser.SpiderParser;

/**
 * The SpiderTask representing a spidering task performed during the Spidering process.
 */
public class SpiderTask implements Runnable {

	/** The parent spider. */
	private Spider parent;

	/**
	 * The history reference to the database record where the request message has been partially
	 * filled in. Cannot be null.
	 */
	private HistoryReference reference;

	/** The depth of crawling where the uri was found. */
	private int depth;

	/** The Constant log. */
	private static final Logger log = Logger.getLogger(SpiderTask.class);

	/**
	 * Instantiates a new spider task using the target URI. The purpose of this task is to crawl the
	 * given uri, using the provided method, find any other uris in the fetched resource and create
	 * other tasks.
	 * 
	 * 
	 * @param parent the spider controlling the crawling process
	 * @param uri the uri that this task should process
	 * @param depth the depth where this uri is located in the spidering process
	 * @param method the HTTP method that should be used to fetch the resource
	 * 
	 */
	public SpiderTask(Spider parent, URI uri, int depth, String method) {
		this(parent, uri, depth, method, null);
	}

	/**
	 * Instantiates a new spider task using the target URI. The purpose of this task is to crawl the
	 * given uri, using the provided method, find any other uris in the fetched resource and create
	 * other tasks.
	 * 
	 * <p>
	 * The body of the request message is also provided in the {@literal requestBody} parameter and
	 * will be used when fetching the resource from the specified uri.
	 * </p>
	 * 
	 * @param parent the spider controlling the crawling process
	 * @param uri the uri that this task should process
	 * @param depth the depth where this uri is located in the spidering process
	 * @param method the HTTP method that should be used to fetch the resource
	 * 
	 */
	public SpiderTask(Spider parent, URI uri, int depth, String method, String requestBody) {
		super();
		this.parent = parent;
		this.depth = depth;

		// Check if cookies should be added
		List<HttpCookie> cookies = prepareCookies(uri.toString());

		// Log the new task
		if (log.isDebugEnabled()) {
			if (cookies != null && cookies.size() > 0)
				log.debug("New task submitted for uri: " + uri + " with cookies: " + cookies);
			else
				log.debug("New task submitted for uri: " + uri + " without cookies.");
		}

		// Create a new HttpMessage that will be used for the request, add the cookies (if any) and
		// persist it in the database using HistoryReference
		try {
			HttpMessage msg = new HttpMessage(new HttpRequestHeader(method, uri, HttpHeader.HTTP11));
			if (cookies != null)
				msg.setCookies(cookies);
			if (requestBody != null) {
				msg.getRequestHeader().setContentLength(requestBody.length());
				msg.setRequestBody(requestBody);
			}
			this.reference = new HistoryReference(parent.getModel().getSession(), HistoryReference.TYPE_SPIDER_TASK,
					msg);
		} catch (HttpMalformedHeaderException e) {
			log.error("Error while building HttpMessage for uri: " + uri, e);
		} catch (SQLException e) {
			log.error("Error while persisting HttpMessage for uri: " + uri, e);
		}
	}

	/**
	 * Prepare the list of the cookies that should be sent in the request message for the given uri.
	 * 
	 * @param uriS the uri
	 * @return the list of cookies to send with the request, or null if no cookies should be sent
	 */
	private List<HttpCookie> prepareCookies(String uriS) {
		if (parent.getSpiderParam().isSendCookies()) {
			java.net.URI uri = null;
			try {
				uri = new java.net.URI(uriS);
			} catch (URISyntaxException e) {
				log.error("Error while preparing cookies. ", e);
			}
			return parent.getCookieManager().getCookieStore().get(uri);
		}
		return null;
	}

	/* (non-Javadoc)
	 * 
	 * @see java.lang.Runnable#run() */
	@Override
	public void run() {

		// Log the task start
		if (log.isDebugEnabled()) {
			try {
				log.debug("Spider Task Started. Processing uri at depth " + depth
						+ " using already constructed message:  "
						+ reference.getHttpMessage().getRequestHeader().getURI());
			} catch (Exception e1) { // Ignore it
			}
		}

		// Check if the should stop
		if (parent.isStopped()) {
			log.debug("Spider process is stopped. Skipping crawling task...");
			return;
		}
		if (reference == null) {
			log.warn("Null URI. Skipping crawling task: " + this);
			return;
		}

		// Check if the crawling process is paused and do any "before execution" processing
		parent.preTaskExecution();

		// Fetch the resource
		HttpMessage msg = null;
		try {
			msg = fetchResource();
		} catch (Exception e) {
			log.error("An error occured while fetching resoure: " + e.getMessage(), e);
			return;
		}

		// Check if the should stop
		if (parent.isStopped()) {
			log.debug("Spider process is stopped. Skipping crawling task...");
			return;
		}
		// Check if the crawling process is paused
		parent.checkPauseAndWait();

		// Notify the SpiderListeners that a resource was read
		parent.notifyListenersReadURI(msg);

		// Check the parse filters to see if the resource should be skipped from parsing
		boolean isFiltered = false;
		for (ParseFilter filter : parent.getController().getParseFilters())
			if (filter.isFiltered(msg)) {
				if (log.isInfoEnabled())
					log.info("Resource fetched, but will not be parsed due to a ParseFilter rule: "
							+ msg.getRequestHeader().getURI());
				isFiltered = true;
				break;
			}

		// Check if the should stop
		if (parent.isStopped()) {
			log.debug("Spider process is stopped. Skipping crawling task...");
			return;
		}
		// Check if the crawling process is paused
		parent.checkPauseAndWait();

		// Process resource, if this is not the maximum depth
		if (!isFiltered && depth < parent.getSpiderParam().getMaxDepth())
			processResource(msg);

		// Update the progress and check if the spidering process should stop
		parent.postTaskExecution();
		log.debug("Spider Task finished.");
	}

	/**
	 * Process a resource, adding the cookies & searching for links (uris) to other resources.
	 * 
	 * @param msg the HTTP Message
	 */
	private void processResource(HttpMessage msg) {
		// Add the cookies
		if (parent.getSpiderParam().isSendCookies()) {

			List<HttpCookie> cookies = msg.getResponseHeader().getHttpCookies();
			if (!cookies.isEmpty()) {
				CookieStore store = parent.getCookieManager().getCookieStore();
				java.net.URI uri = null;
				try {
					uri = new java.net.URI(msg.getRequestHeader().getURI().toString());
				} catch (URISyntaxException e1) {
					log.error("Error while building URI for cookie adding", e1);
				}
				for (HttpCookie c : cookies)
					store.add(uri, c);
			}
		}

		// Parse the resource
		List<SpiderParser> parsers = parent.getController().getParsers(msg);
		Source source = new Source(msg.getResponseBody().toString());
		for (SpiderParser parser : parsers)
			parser.parseResource(msg, source, depth);
	}

	/**
	 * Fetches a resource.
	 * 
	 * @return the response http message
	 * @throws HttpException the http exception
	 * @throws IOException Signals that an I/O exception has occurred.
	 * @throws SQLException
	 */
	private HttpMessage fetchResource() throws HttpException, IOException, SQLException, SocketTimeoutException {

		// Build fetch the request message from the database
		HttpMessage msg;
		msg = reference.getHttpMessage();
		msg.getRequestHeader().setHeader(HttpHeader.IF_MODIFIED_SINCE, null);
		msg.getRequestHeader().setHeader(HttpHeader.IF_NONE_MATCH, null);

		// Remove the history reference from the database, as it's not used anymore
		reference.delete();

		// Check if there is a custom user agent
		if (parent.getSpiderParam().getUserAgent() != null)
			msg.getRequestHeader().setHeader(HttpHeader.USER_AGENT, parent.getSpiderParam().getUserAgent());

		// Fetch the page
		if (parent.getHttpSender() != null)
			parent.getHttpSender().sendAndReceive(msg);

		return msg;

	}

}
