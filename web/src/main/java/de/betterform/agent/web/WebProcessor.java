/* Copyright 2008 - Joern Turner, Lars Windauer */
/* Licensed under the terms of BSD and Apache 2 Licenses */
package de.betterform.agent.web;

import javax.servlet.ServletContext;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import de.betterform.agent.web.event.DefaultUIEventImpl;
import de.betterform.agent.web.event.UIEvent;
import de.betterform.agent.web.flux.FluxProcessor;
import de.betterform.agent.web.servlet.HttpRequestHandler;
import de.betterform.generator.UIGenerator;
import de.betterform.generator.XSLTGenerator;
import de.betterform.xml.config.Config;
import de.betterform.xml.config.XFormsConfigException;
import de.betterform.xml.events.BetterFormEventNames;
import de.betterform.xml.events.DOMEventNames;
import de.betterform.xml.events.XFormsEventNames;
import de.betterform.xml.events.XMLEvent;
import de.betterform.xml.xforms.XFormsProcessorImpl;
import de.betterform.xml.xforms.XFormsElement;
import de.betterform.xml.xforms.XFormsProcessor;
import de.betterform.xml.xforms.exception.XFormsException;
import de.betterform.xml.xslt.TransformerService;
import org.w3c.dom.Node;
import org.w3c.dom.events.Event;
import org.w3c.dom.events.EventListener;
import org.w3c.dom.events.EventTarget;
import org.w3c.xforms.XFormsModelElement;
import org.xml.sax.InputSource;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/**
 * Superclass for Adapters used in web applications. Does minimal event listening on the processor and provides
 * a common base to build webadapers.
 *
 * @author Joern Turner
 * @version $Id: WebAdapter.java 2875 2007-09-28 09:43:30Z lars $
 * @see de.betterform.agent.web.flux.FluxProcessor
 * @see de.betterform.agent.web.servlet.PlainHtmlProcessor
 */
//public class WebProcessor extends XFormsProcessor implements EventListener {
public class WebProcessor implements XFormsProcessor, EventListener {

    /**
     * Defines the key for accessing (HTTP) session ids.
     */
    public static final String USERAGENT = "useragent";
    public static final String REQUEST_URI = "requestURI";
    public static final String CONTEXTROOT = "contextroot";
    public static final String SESSION_ID = "betterform.session.id";
    public static final String REALPATH = "webapp.realpath";
    public static final String XSL_PARAM_NAME = "xslt";
    public static final String ACTIONURL_PARAM_NAME = "action_url";
    public static final String UIGENERATOR = "betterform.UIGenerator";
    public static final String REFERER = "betterform.referer";
    public static final String ADAPTER_PREFIX = "A";

    public static final String ALTERNATIVE_ROOT = "ResourcePath";

    //todo:review - can be deleted when ehcache is in place
    String KEEPALIVE_PULSE = "keepalive";
    protected XFormsProcessor xformsProcessor;
    protected EventTarget root;
    protected HttpRequestHandler httpRequestHandler;
    protected XMLEvent exitEvent = null;
    protected String contextRoot;
    protected String key;
    protected transient HttpServletRequest request;
    protected transient HttpServletResponse response;
    protected transient HttpSession httpSession;
    protected transient ServletContext context;
    protected boolean isXFormsPresent = false;
    protected Config configuration;//    private String requestURI;
    private static final Log LOGGER = LogFactory.getLog(FluxProcessor.class);
    private String uploadDestination;
    private String useragent;
    private String uploadDir;
    protected UIGenerator uiGenerator;

    public WebProcessor() {
        this.xformsProcessor = new XFormsProcessorImpl();
    }

    public void configure() throws XFormsException {
        this.key = generateXFormsSessionKey();
        initConfig();
        WebUtil.storeCookies(request, this);
        WebUtil.setContextParams(request, httpSession, this, this.key);
        WebUtil.copyHttpHeaders(request, this);
        setLocale();
        configureUpload();
        configureSession();
    }

    /**
     * the string identifier generated by this XFormsSession for use in the client
     *
     * @return the string identifier generated by this XFormsSession for use in the client
     */
    public String getKey() {
        return this.key;
    }

    public void setRequest(HttpServletRequest request) {
        this.request = request;
    }

    public void setResponse(HttpServletResponse response) {
        this.response = response;
    }

    public void setHttpSession(HttpSession httpSession) {
        this.httpSession = httpSession;
    }

    public void setUseragent(String useragent) {
        this.useragent = useragent;
    }

    /**
     * passes an XForms document to the betterForm Adapter. It supports 4 different ways of passing
     * a XForms:<br/>
     * a. passing a request param with a URI e.g. form=/forms/foo.xhtml<br/>
     * b. passing a URI as request attribute<br/>
     * c. passing a request attribute of 'XFORMS_NODE' with a value containing the input document<br/>
     * d. passing an inputstream containing the document<br/>
     * e. passing a SAX inputsource containing the document.
     *
     * @throws de.betterform.xml.xforms.exception.XFormsException
     *
     */
    public void setXForms() throws XFormsException {
        if (this.xformsProcessor == null) {
            throw new XFormsException("Adapter has not yet been initialized");
        }
        if (request.getParameter(WebFactory.FORM_PARAM_NAME) != null) {

            try {
                String formURI = WebUtil.getFormUrlAsString(this.request);
                this.xformsProcessor.setXForms(new URI(formURI));
                //set the base URI explicitly here cause it must match the path of the loaded form
                setBaseURI(formURI);
            } catch (URISyntaxException e) {
                throw new XFormsException("URI is malformed: " + e);
            } catch (MalformedURLException e) {
                throw new XFormsException("URL is malformed: " + e);
            } catch (UnsupportedEncodingException e) {
                throw new XFormsException("Encoding of form Url is not supported: " + e);
            }
        } else if (request.getAttribute(WebFactory.XFORMS_URI) != null) {
            String uri = (String) request.getAttribute(WebFactory.XFORMS_URI);

            try {
                this.xformsProcessor.setXForms(new URI(WebUtil.decodeUrl(uri, request)));
            } catch (URISyntaxException e) {
                throw new XFormsException("URI is malformed: " + e);
            } catch (UnsupportedEncodingException e) {
                throw new XFormsException("Encoding of form Url is not supported: " + e);
            }
        } else if (request.getAttribute(WebFactory.XFORMS_NODE) != null) {
            Node node = (Node) request.getAttribute(WebFactory.XFORMS_NODE);
            this.xformsProcessor.setXForms(node);
        } else if (request.getAttribute(WebFactory.XFORMS_INPUTSTREAM) != null) {
            InputStream inputStream = (InputStream) request.getAttribute(WebFactory.XFORMS_INPUTSTREAM);
            this.xformsProcessor.setXForms(inputStream);
        } else if (request.getAttribute(WebFactory.XFORMS_INPUTSOURCE) != null) {
            InputSource inputSource = (InputSource) request.getAttribute(WebFactory.XFORMS_INPUTSOURCE);
            this.xformsProcessor.setXForms(inputSource);
        } else {
            throw new XFormsException("no XForms input document found - init failed");
        }
        isXFormsPresent = true;

        if (this.configuration.getProperty("webprocessor.doIncludes").equals("true")) {
            doIncludes();
        }
    }

    private void doIncludes() {
        try {
            Node input = getXForms();
            String xsltPath = this.configuration.getProperty(WebFactory.XSLT_PATH_PROPERTY);
            XSLTGenerator xsltGenerator = setupTransformer(xsltPath, "include.xsl");
            String baseURI = getBaseURI();
            String uri = baseURI.substring(0, baseURI.lastIndexOf("/") + 1);

            xsltGenerator.setParameter("root", uri);
            DOMResult result = new DOMResult();
            DOMSource source = new DOMSource(input);
            xsltGenerator.setInput(source);
            xsltGenerator.setOutput(result);
            xsltGenerator.generate();
            setXForms(result.getNode());

        } catch (XFormsException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (URISyntaxException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

    }

    public void setXForms(Node node) throws XFormsException {
        this.xformsProcessor.setXForms(node);
    }

    public void setXForms(URI uri) throws XFormsException {
        this.xformsProcessor.setXForms(uri);
    }

    public void setXForms(InputStream inputStream) throws XFormsException {
        this.xformsProcessor.setXForms(inputStream);
    }

    public String getBaseURI() {
        return this.xformsProcessor.getBaseURI();
    }

    public void setXForms(InputSource inputSource) throws XFormsException {
        this.xformsProcessor.setXForms(inputSource);
    }

    public void setBaseURI(String s) {
        this.xformsProcessor.setBaseURI(s);
    }

    public void setConfigPath(String s) throws XFormsException {
        this.xformsProcessor.setConfigPath(s);
    }

    public void setContext(Map map) {
        this.xformsProcessor.setContext(map);
    }

    public void setContextParam(String s, Object o) {
        this.xformsProcessor.setContextParam(s, o);
    }

    public Object getContextParam(String s) {
        return this.xformsProcessor.getContextParam(s);
    }

    public Object removeContextParam(String s) {
        return this.xformsProcessor.removeContextParam(s);
    }

    //todo: parse accept-language header to decide about locale
    public void setLocale() throws XFormsException {
        if (Config.getInstance().getProperty(XFormsProcessorImpl.BETTERFORM_ENABLE_L10N).equals("true")) {

            //[1] check for request param 'lang' - todo: might need refinement later to not clash with using apps
            String locale = request.getParameter("lang");
            if (request.getParameter("lang") != null) {
                if (WebProcessor.LOGGER.isDebugEnabled()) {
                    WebProcessor.LOGGER.debug("using 'lang' Url parameter: " + request.getParameter("lang"));
                }
                this.xformsProcessor.setLocale(request.getParameter("lang"));
            } else if ((String) request.getAttribute("lang") != null) {
                if (WebProcessor.LOGGER.isDebugEnabled()) {
                    WebProcessor.LOGGER.debug("using request Attribute 'lang': " + request.getParameter("lang"));
                }
                this.xformsProcessor.setLocale((String) request.getAttribute("lang"));
            } else if (!(Config.getInstance().getProperty("preselect-language").equals(""))) {
                if (WebProcessor.LOGGER.isDebugEnabled()) {
                    WebProcessor.LOGGER.debug("using configured lang setting from Config: " + Config.getInstance().getProperty("preselect-language"));
                }
                this.xformsProcessor.setLocale(Config.getInstance().getProperty("preselect-language"));
            } else if (request.getHeader("accept-language") != null) {
                if (WebProcessor.LOGGER.isDebugEnabled()) {
                    WebProcessor.LOGGER.debug("using accept-language header: " + request.getHeader("accept-language"));
                }
                //todo:improve to support priority for language setting
                String s = request.getHeader("accept-language");
                this.xformsProcessor.setLocale(s.substring(0, 2));
            }
        } else {
            //fallback default
            this.xformsProcessor.setLocale("en");
        }
    }

    public void setLocale(String locale) throws XFormsException {
        this.xformsProcessor.setLocale(locale);
    }

    /**
     * Makes sure to return the context in which the processor is running.
     * It uses the current httpSession if context is null. If the context
     * is explicitly set with setContext() it returns this context.
     *
     * @return the context in which the processor is running.
     */
    public ServletContext getContext() {
        // Return the betterform context when set
        if (this.context != null) {
            return context;
        }
        // otherwise get the context from the http session.
        return httpSession.getServletContext();
    }

    /**
     * Overwrites the (servlet) context to use. If not set
     * the http session is used to get the servlet context.
     *
     * @param context in which this processor is executed.
     */
    public void setContext(ServletContext context) {
        this.context = context;
    }

    /**
     * initialize the Adapter. This is necessary cause often the using
     * application will need to configure the Adapter before actually using it.
     *
     * @throws de.betterform.xml.xforms.exception.XFormsException
     *
     */
    public void init() throws XFormsException {
        if (noHttp()) {
            throw new XFormsException("request, response and session object are undefined");
        }
        addEventListeners();

        // init processor
        this.xformsProcessor.init();
    }

    public Node getXForms() throws XFormsException {
        return this.xformsProcessor.getXForms();
    }

    public XFormsModelElement getXFormsModel(String s) throws XFormsException {
        return this.xformsProcessor.getXFormsModel(s);
    }

    public XMLEvent checkForExitEvent() {
        return this.exitEvent;
    }

    /**
     * Dispatch a UIEvent to trigger some XForms processing such as updating
     * of values or execution of triggers.
     *
     * @param event an application specific event
     * @throws de.betterform.xml.xforms.exception.XFormsException
     *
     */
    public void handleUIEvent(UIEvent event) throws XFormsException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Event " + event.getEventName() + " dispatched");
            LOGGER.debug("Event target: " + event.getId());
            /*   try {
            if(this.xformsProcessor != null){
            DOMUtil.prettyPrintDOM(this.xformsProcessor.getXMLContainer(),System.out);
            }
            } catch (TransformerException e) {
            throw new XFormsException(e);
            }
            }  */
        }
    }

    /**
     * listen to processor and add a DefaultUIEventImpl object to the
     * EventQueue.
     *
     * @param event the handled DOMEvent
     */
    public void handleEvent(Event event) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("handleEvent: " + event.getType());
        }
    }

    /**
     * terminates the XForms processing. right place to do cleanup of
     * resources.
     *
     * @throws de.betterform.xml.xforms.exception.XFormsException
     *
     */
    public void shutdown() throws XFormsException {
        // shutdown processor
        if (this.xformsProcessor != null) {
            this.xformsProcessor.shutdown();
            this.xformsProcessor = null;
        }

        // deregister for interaction events if any
        if (this.root != null) {
            this.root.removeEventListener(DOMEventNames.ACTIVATE, this, true);
            this.root.removeEventListener(XFormsEventNames.BINDING_EXCEPTION, this, true);
            this.root.removeEventListener(XFormsEventNames.COMPUTE_EXCEPTION, this, true);
            /*
            this.root.removeEventListener(XFormsEventNames.DISABLED, this, true);
            this.root.removeEventListener(XFormsEventNames.ENABLED, this, true);
             */
            this.root.removeEventListener(XFormsEventNames.FOCUS, this, false);
            this.root.removeEventListener(DOMEventNames.FOCUS_IN, this, true);
            this.root.removeEventListener(DOMEventNames.FOCUS_OUT, this, true);
            this.root.removeEventListener(XFormsEventNames.HELP, this, true);
            this.root.removeEventListener(XFormsEventNames.HINT, this, true);
            this.root.removeEventListener(XFormsEventNames.INVALID, this, true);
            this.root.removeEventListener(XFormsEventNames.IN_RANGE, this, true);
            this.root.removeEventListener(XFormsEventNames.OUT_OF_RANGE, this, true);
            this.root.removeEventListener(BetterFormEventNames.LOAD_URI, this, true);
            this.root.removeEventListener(XFormsEventNames.LINK_EXCEPTION, this, true);
            this.root.removeEventListener(XFormsEventNames.LINK_ERROR, this, true);
            this.root.removeEventListener(XFormsEventNames.MODEL_CONSTRUCT, this, true);
            this.root.removeEventListener(XFormsEventNames.MODEL_CONSTRUCT_DONE, this, true);
            this.root.removeEventListener(XFormsEventNames.NEXT, this, true);
            this.root.removeEventListener(XFormsEventNames.PREVIOUS, this, true);
            this.root.removeEventListener(XFormsEventNames.READY, this, true);
            this.root.removeEventListener(BetterFormEventNames.RENDER_MESSAGE, this, true);
            this.root.removeEventListener(BetterFormEventNames.REPLACE_ALL, this, true);
            this.root.removeEventListener(XFormsEventNames.SUBMIT, this, true);
            this.root.removeEventListener(XFormsEventNames.SUBMIT_DONE, this, true);
            this.root.removeEventListener(XFormsEventNames.SUBMIT_ERROR, this, true);
            this.root.removeEventListener(XFormsEventNames.VALUE_CHANGED, this, true);
            this.root.removeEventListener(XFormsEventNames.VERSION_EXCEPTION, this, true);
            this.root.removeEventListener(XFormsEventNames.VALID, this, true);
            this.root.removeEventListener(XFormsEventNames.SELECT, this, true);
            this.root.removeEventListener(XFormsEventNames.DESELECT, this, true);


            this.root = null;
        }
    }

    public boolean dispatch(String id, String event) throws XFormsException {
        return this.xformsProcessor.dispatch(id, event);
    }

    /**
     * dispatches an Event to an Element specified by parameter 'id' and allows to set all events properties and
     * pass a context info
     *
     * @param targetId   the id identifying the Element to dispatch to
     * @param eventType  the type of Event to dispatch identified by a string
     * @param info       an implementation-specific context info object
     * @param bubbles    true if event bubbles
     * @param cancelable true if event is cancelable
     * @return <code>true</code> if the event has been cancelled during dispatch,
     *         otherwise <code>false</code>.
     * @throws de.betterform.xml.xforms.exception.XFormsException
     *
     */
    public boolean dispatch(String targetId, String eventType, Object info, boolean bubbles, boolean cancelable) throws XFormsException {
        return this.xformsProcessor.dispatch(targetId, eventType, info, bubbles, cancelable);
    }

    public XFormsElement lookup(String s) {
        return this.xformsProcessor.lookup(s);
    }

    public void handleEventException(Exception e) {
        this.xformsProcessor.handleEventException(e);
    }

    public void setControlValue(String s, String s1) throws XFormsException {
        this.xformsProcessor.setControlValue(s, s1);
    }

    public void setUploadValue(String s, String s1, String s2, byte[] bytes) throws XFormsException {
        this.xformsProcessor.setUploadValue(s, s1, s2, bytes);
    }

    public boolean isFileUpload(String s, String s1) throws XFormsException {
        return this.xformsProcessor.isFileUpload(s, s1);
    }

    public void setRepeatIndex(String s, int i) throws XFormsException {
        this.xformsProcessor.setRepeatIndex(s, i);
    }

    /**
     * set the upload location. This string represents the destination (data-sink) for uploads.
     *
     * @param destination a String representing the location where to store uploaded files/data.
     */
    public void setUploadDestination(String destination) {
        this.uploadDestination = destination;
    }

    /**
     * processes the request after init.
     *
     * @throws java.io.IOException
     * @throws de.betterform.xml.xforms.exception.XFormsException
     *
     * @throws java.net.URISyntaxException
     */
    public synchronized void handleRequest() throws XFormsException {
        boolean updating = false; //this will become true in case PlainHtmlProcessor is in use
        WebUtil.nonCachingResponse(response);

        try {
            if (request.getMethod().equalsIgnoreCase("POST")) {
                updating = true;
                // updating ... - this is only called when PlainHtmlProcessor is in use
                UIEvent uiEvent = new DefaultUIEventImpl();
                uiEvent.initEvent("http-request", null, request);
                handleUIEvent(uiEvent);
            }

            XMLEvent exitEvent = checkForExitEvent();
            if (exitEvent != null) {
                handleExit(exitEvent);
            } else {
                String referer = null;

                if (updating) {
                    // updating ... - this is only called when PlainHtmlProcessor is in use
//                    referer = (String) getProperty(XFormsSession.REFERER);
                    referer = (String) getContextParam(REFERER);
//                    setProperty("update", "true");
                    setContextParam("update", "true");
                    String forwardTo = request.getContextPath() + "/view?sessionKey=" + getKey() + "&referer=" + referer;
                    response.sendRedirect(response.encodeRedirectURL(forwardTo));
                } else {
                    //initing ...
                    referer = request.getQueryString();

                    response.setContentType(WebUtil.HTML_CONTENT_TYPE);
                    //we got an initialization request (GET) - the session is not registered yet
                    this.uiGenerator = createUIGenerator();
                    //store UIGenerator in this session as a property
                    setContextParam(UIGENERATOR, uiGenerator);
                    //store queryString as 'referer' in XFormsSession
                    setContextParam(REFERER, request.getContextPath() + request.getServletPath() + "?" + referer);
                    //actually register the XFormsSession with the manager
                    // getManager().addXFormsSession(this);
                    Cache cache = CacheManager.getInstance().getCache("xfSessionCache");
                    cache.put(new net.sf.ehcache.Element(this.getKey(), this));

                    //todo:check if it's still necessary to set an attribute to the session
                    httpSession.setAttribute("TimeStamp", System.currentTimeMillis());

                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

                    generateUI(this.xformsProcessor.getXForms(), outputStream);

                    response.setContentLength(outputStream.toByteArray().length);
                    response.getOutputStream().write(outputStream.toByteArray());
                }
            }
        } catch (IOException e) {
            throw new XFormsException(e);
        } catch (URISyntaxException e) {
            throw new XFormsException(e);
        }
        WebUtil.printSessionKeys(this.httpSession);
    }

    protected void generateUI(Object input, Object output) throws XFormsException {
        uiGenerator.setInput(input);
        uiGenerator.setOutput(output);
        uiGenerator.generate();
    }

    /**
     * Handles XForms exist events. There are only 2 situations when this occurs. A load action or a submission/@replace='all'
     * happens during XForms Model init.
     *
     * @param exitEvent the XMLEvent representing the exit condition
     * @throws java.io.IOException occurs if the redirect fails
     */
    public void handleExit(XMLEvent exitEvent) throws IOException {
        if (BetterFormEventNames.REPLACE_ALL.equals(exitEvent.getType())) {
            response.sendRedirect(response.encodeRedirectURL(request.getContextPath() + "/SubmissionResponse?sessionKey=" + getKey()));
        } else if (BetterFormEventNames.LOAD_URI.equals(exitEvent.getType())) {
            if (exitEvent.getContextInfo("show") != null) {
                String loadURI = (String) exitEvent.getContextInfo("uri");

                //kill XFormsSession
                WebUtil.removeSession(getKey());
                if (WebProcessor.LOGGER.isDebugEnabled()) {
                    WebProcessor.LOGGER.debug("loading: " + loadURI);
                }
                response.sendRedirect(response.encodeRedirectURL(loadURI));
            }
        }
        WebProcessor.LOGGER.debug("************************* EXITED DURING XFORMS MODEL INIT *************************");
    }

    /**
     * close the XFormsSession in case of an exception. This will close the WebAdapter holding the betterForm Processor instance,
     * remove the XFormsSession from the Manager and redirect to the error page.
     *
     * @param e the root exception causing the close
     * @throws java.io.IOException
     */
    public void close(Exception e) throws IOException {
        // attempt to shutdown processor
        if (this.xformsProcessor != null) {
            try {
                this.xformsProcessor.shutdown();
            } catch (XFormsException xfe) {
                WebProcessor.LOGGER.error("Message: " + xfe.getMessage() + " Cause: " + xfe.getCause());
            }
        }

        // store exception
        httpSession.setAttribute("betterform.exception", e);

        //remove session from XFormsSessionManager
        // getManager().deleteXFormsSession(this.key);
        WebUtil.removeSession(this.key);

        // redirect to error page (after encoding session id if required)
        response.sendRedirect(response.encodeRedirectURL(request.getContextPath() + "/" +
                configuration.getProperty(WebFactory.ERROPAGE_PROPERTY)));
    }

    /**
     * passes the betterform-defaults.xml config file to betterForm Processor.
     *
     * @throws de.betterform.xml.xforms.exception.XFormsException
     *
     */
    protected void initConfig() throws XFormsException {
        final String initParameter = getContext().getInitParameter(WebFactory.BETTERFORM_CONFIG_PATH);
        String configPath = WebFactory.resolvePath(initParameter, getContext());
        if ((configPath != null) && !(configPath.equals(""))) {
            this.xformsProcessor.setConfigPath(configPath);
            this.configuration = Config.getInstance();
        }
    }

    /**
     * allows to perform form specific initialization tasks like setting individual session lifetime, support
     * of custom xslt switching etc.
     *
     * @throws XFormsException
     */
    protected void configureSession() throws XFormsException {
        /*
        Node n = this.xformsProcessor.getXForms();
        if (!(n.getNodeType() == Node.DOCUMENT_NODE)) {
        throw new XFormsException("returned Node is no Document");
        }

        Document hostDocument = (Document) this.xformsProcessor.getXForms();
        Element root = hostDocument.getDocumentElement();

        Element keepAlive = DOMUtil.findFirstChildNS(root, NamespaceConstants.BETTERFORM_NS, "keepalive");
        if (keepAlive != null) {
        String pulse = keepAlive.getAttributeNS(null, "pulse");
        if (!(pulse == null || pulse.equals(""))) {
        this.xformsProcessor.setContextParam(KEEPALIVE_PULSE, pulse);
        }
        }
         */
    }

    protected String generateXFormsSessionKey() {
        return "" + System.currentTimeMillis();
    }

    protected HttpRequestHandler getHttpRequestHandler() {
        if (this.httpRequestHandler == null) {
            this.httpRequestHandler = new HttpRequestHandler(this);
            this.httpRequestHandler.setUploadRoot(this.uploadDestination);
            this.httpRequestHandler.setSessionKey(this.getKey());
        }

        return this.httpRequestHandler;
    }


    /**
     * creates and configures a UIGenerator that transcodes the XHTML/XForms document into the desired target format.
     * <p/>
     * todo: make configuration of xsl file more flexible
     * todo: add baseURI as stylesheet param
     *
     * @return an instance of an UIGenerator
     * @throws java.net.URISyntaxException
     * @throws de.betterform.xml.xforms.exception.XFormsException
     *
     */
    protected UIGenerator createUIGenerator() throws URISyntaxException, XFormsException {
        String xsltPath = configuration.getProperty(WebFactory.XSLT_PATH_PROPERTY);
        String relativeUris = configuration.getProperty(WebFactory.RELATIVE_URI_PROPERTY);

        //todo: should the following two be removed? Use fixed resources dir as convention now - user shouldn't need to touch that
        String scriptPath = configuration.getProperty(WebFactory.SCRIPT_PATH_PROPERTY);
        String cssPath = configuration.getProperty(WebFactory.CSS_PATH_PROPERTY);

        //todo: extract method
        String xslFile = request.getParameter(XSL_PARAM_NAME);
        if (xslFile == null) {
//            xslFile = configuration.getProperty(WebFactory.XSLT_DEFAULT_PROPERTY);
            xslFile = configuration.getStylesheet(this.useragent);
        }

        XSLTGenerator generator = setupTransformer(xsltPath, xslFile);

        if (relativeUris.equals("true")) {
            generator.setParameter("contextroot", ".");
        } else {
            generator.setParameter("contextroot", WebUtil.getContextRoot(request));
        }
        generator.setParameter("sessionKey", getKey());
        if (getContextParam(KEEPALIVE_PULSE) != null) {
            generator.setParameter("keepalive-pulse", getContextParam(KEEPALIVE_PULSE));
        }

        if (useragent.equalsIgnoreCase("dojo") || useragent.equalsIgnoreCase("dojodev")) {
            generator.setParameter("action-url", getActionURL(true));
        } else if (useragent.equalsIgnoreCase("html")) {
            generator.setParameter("action-url", getActionURL(false));
        } else {
            throw new XFormsConfigException("Invalid useragent: " + useragent + "'");
        }
        if (request.getParameter("debug") != null && configuration.getProperty("betterform.debug-allowed").equals("true")) {
            generator.setParameter("debug-enabled", "true");
        }
        String selectorPrefix = Config.getInstance().getProperty(HttpRequestHandler.SELECTOR_PREFIX_PROPERTY,
                HttpRequestHandler.SELECTOR_PREFIX_DEFAULT);
        generator.setParameter("selector-prefix", selectorPrefix);
        String removeUploadPrefix = Config.getInstance().getProperty(HttpRequestHandler.REMOVE_UPLOAD_PREFIX_PROPERTY,
                HttpRequestHandler.REMOVE_UPLOAD_PREFIX_DEFAULT);
        generator.setParameter("remove-upload-prefix", removeUploadPrefix);
        String dataPrefix = Config.getInstance().getProperty("betterform.web.dataPrefix");
        generator.setParameter("data-prefix", dataPrefix);

        String triggerPrefix = Config.getInstance().getProperty("betterform.web.triggerPrefix");
        generator.setParameter("trigger-prefix", triggerPrefix);

//        generator.setParameter("user-agent", request.getHeader("User-Agent"));

//        generator.setParameter("scripted", String.valueOf(isScripted()));
        if (scriptPath != null) {
            generator.setParameter("scriptPath", scriptPath);
        }
        if (cssPath != null) {
            generator.setParameter("CSSPath", cssPath);
        }

//        String compressedJS = Config.getInstance().getProperty("betterform.js.compressed", "false");
//        generator.setParameter("js-compressed", compressedJS);

        return generator;
    }

    private XSLTGenerator setupTransformer(String xsltPath, String xslFile) throws URISyntaxException {
        TransformerService transformerService = (TransformerService) getContext().getAttribute(TransformerService.class.getName());
        URI uri = new File(WebFactory.resolvePath(xsltPath, getContext())).toURI().resolve(new URI(xslFile));

        XSLTGenerator generator = new XSLTGenerator();
        generator.setTransformerService(transformerService);
        generator.setStylesheetURI(uri);
        return generator;
    }

    /**
     * determines the value for the HTML form/@action attribute in the transcoded page
     *
     * @param scripted Client allows scripting or not
     * @return the action url to be used in the HTML form
     */
    protected String getActionURL(boolean scripted) {
        String defaultActionURL = null;

        if (useragent.equalsIgnoreCase("dojo")) {
            defaultActionURL = WebUtil.getRequestURI(request);
        } else {
            defaultActionURL = this.request.getRequestURI();
        }
        String encodedDefaultActionURL = response.encodeURL(defaultActionURL);
        int sessIdx = encodedDefaultActionURL.indexOf(";jsession");
        String sessionId = null;
        if (sessIdx > -1) {
            sessionId = encodedDefaultActionURL.substring(sessIdx);
        }
        String actionURL = request.getParameter(ACTIONURL_PARAM_NAME);
        if (null == actionURL) {
            actionURL = encodedDefaultActionURL;
        } else if (null != sessionId) {
            actionURL += sessionId;
        }

        WebProcessor.LOGGER.debug("actionURL: " + actionURL);
        // encode the URL to allow for session id rewriting
        actionURL = response.encodeURL(actionURL);
        return actionURL;
    }

    private void configureUpload() throws XFormsConfigException {
        //allow absolute paths otherwise resolve relative to the servlet context
        this.uploadDir = Config.getInstance().getProperty("uploadDir");
        if (uploadDir == null) {
            throw new XFormsConfigException("upload dir is not set in betterform-config.xml");
        }
        if (!new File(uploadDir).isAbsolute()) {
            uploadDir = WebFactory.resolvePath(uploadDir, getContext());
        }

        setUploadDestination(new File(uploadDir).getAbsolutePath());
    }

    private void addEventListeners() throws XFormsException {
        // get docuent root as event target in order to capture all events
        this.root = (EventTarget) this.xformsProcessor.getXForms();

        // interaction events my occur during init so we have to register before
        this.root.addEventListener(DOMEventNames.ACTIVATE, this, true);
        this.root.addEventListener(XFormsEventNames.BINDING_EXCEPTION, this, true);
        this.root.addEventListener(XFormsEventNames.COMPUTE_EXCEPTION, this, true);
        /*
        this.root.addEventListener(XFormsEventNames.DISABLED, this, true);
        this.root.addEventListener(XFormsEventNames.ENABLED, this, true);
         */
        this.root.addEventListener(XFormsEventNames.FOCUS, this, false);
        this.root.addEventListener(DOMEventNames.FOCUS_IN, this, true);
        this.root.addEventListener(DOMEventNames.FOCUS_OUT, this, true);
        this.root.addEventListener(XFormsEventNames.HELP, this, true);
        this.root.addEventListener(XFormsEventNames.HINT, this, true);
        this.root.addEventListener(XFormsEventNames.INVALID, this, true);
        this.root.addEventListener(XFormsEventNames.IN_RANGE, this, true);
        this.root.addEventListener(XFormsEventNames.OUT_OF_RANGE, this, true);
        this.root.addEventListener(BetterFormEventNames.LOAD_URI, this, true);
        this.root.addEventListener(XFormsEventNames.LINK_EXCEPTION, this, true);
        this.root.addEventListener(XFormsEventNames.LINK_ERROR, this, true);
        this.root.addEventListener(XFormsEventNames.MODEL_CONSTRUCT, this, true);
        this.root.addEventListener(XFormsEventNames.MODEL_CONSTRUCT_DONE, this, true);
        this.root.addEventListener(XFormsEventNames.NEXT, this, true);
        this.root.addEventListener(XFormsEventNames.PREVIOUS, this, true);
        this.root.addEventListener(XFormsEventNames.READY, this, true);
        this.root.addEventListener(BetterFormEventNames.RENDER_MESSAGE, this, true);
        this.root.addEventListener(BetterFormEventNames.REPLACE_ALL, this, true);
        this.root.addEventListener(XFormsEventNames.SUBMIT, this, true);
        this.root.addEventListener(XFormsEventNames.SUBMIT_DONE, this, true);
        this.root.addEventListener(XFormsEventNames.SUBMIT_ERROR, this, true);
        this.root.addEventListener(XFormsEventNames.VERSION_EXCEPTION, this, true);
        this.root.addEventListener(XFormsEventNames.VALUE_CHANGED, this, true);
        this.root.addEventListener(XFormsEventNames.VALID, this, true);
        this.root.addEventListener(XFormsEventNames.SELECT, this, true);
        this.root.addEventListener(XFormsEventNames.DESELECT, this, true);
    }

    private boolean noHttp() {
        if (this.request != null && this.response != null && this.httpSession != null) {
            return false;
        } else {
            return true;
        }
    }
}
