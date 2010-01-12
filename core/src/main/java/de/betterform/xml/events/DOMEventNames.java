/* Copyright 2008 - Joern Turner, Lars Windauer */
/* Licensed under the terms of BSD and Apache 2 Licenses */
package de.betterform.xml.events;

/**
 * Some event names defined by the DOM Level 2 Spec which are used by XForms.
 *
 * @author Ulrich Nicolas Liss&eacute;
 * @version $Id: DOMEventNames.java 2279 2006-08-23 18:59:39Z unl $
 */
public interface DOMEventNames {

    // DOM notification events

    /**
     * DOM notification event constant.
     */
    String ACTIVATE = "DOMActivate";

    /**
     * DOM notification event constant.
     */
    String FOCUS_IN = "DOMFocusIn";

    /**
     * DOM notification event constant.
     */
    String FOCUS_OUT = "DOMFocusOut";
}
