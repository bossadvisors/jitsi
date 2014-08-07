/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.jabber.extensions.jingle;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;

import java.util.*;

/**
 * Represents <tt>ssrc-group</tt> elements described in XEP-0339.
 *
 * Created by gp on 07/08/14.
 */
public class SourceGroupPacketExtension
        extends AbstractPacketExtension
{

    /**
     * The name of the "ssrc-group" element.
     */
    public static final String ELEMENT_NAME = "ssrc-group";

    /**
     * The namespace for the "ssrc-group" element.
     */
    public static final String NAMESPACE = "urn:xmpp:jingle:apps:rtp:ssma:0";

    /**
     * The name of the payload <tt>id</tt> SDP argument.
     */
    public static final String SEMANTICS_ATTR_NAME = "semantics";

    /**
     * Creates a new {@link SourceGroupPacketExtension} instance with the proper
     * element name and namespace.
     */
    public SourceGroupPacketExtension()
    {
        super(NAMESPACE, ELEMENT_NAME);
    }

    /**
     * Gets the semantics of this source group.
     *
     * @return the semantics of this source group.
     */
    public String getSemantics()
    {
        return getAttributeAsString(SEMANTICS_ATTR_NAME);
    }

    /**
     * Gets the sources of this source group.
     *
     * @return the sources of this source group.
     */
    public List<SourcePacketExtension> getSources()
    {
        return getChildExtensionsOfType(SourcePacketExtension.class);
    }
}