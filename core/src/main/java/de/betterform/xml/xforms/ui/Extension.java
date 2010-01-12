/* Copyright 2008 - Joern Turner, Lars Windauer */
/* Licensed under the terms of BSD and Apache 2 Licenses */
package de.betterform.xml.xforms.ui;

import de.betterform.xml.xforms.model.Model;
import org.w3c.dom.Element;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Implementation of <b>3.5.1 The extension Element</b>.
 *
 * @author Ulrich Nicolas Liss&eacute;
 * @version $Id: Extension.java 3253 2008-07-08 09:26:40Z lasse $
 */
public class Extension extends AbstractUIElement {
    private static final Log LOGGER = LogFactory.getLog(Extension.class);

    /**
     * Creates a new extension element handler.
     *
     * @param element the host document element.
     * @param model the context model.
     */
    public Extension(Element element, Model model) {
        super(element, model);
    }

    /**
     * Returns the logger object.
     *
     * @return the logger object.
     */
    protected Log getLogger() {
        return LOGGER;
    }
}

// end of class
