/*
 * Copyright (c) 2010, 2022 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.internal.util.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 *
 * @author Paul Sandoz
 */
public abstract class AbstractKeyComparatorHashMapTest {

    protected void _test(KeyComparatorHashMap<String, String> k) {
        assertNull(k.get(null));
        assertFalse(k.containsKey(null));

        k.put("a", "a");
        k.put("b", "b");
        k.put("c", "c");

        assertNull(k.get(null));
        assertEquals("a", k.get("a"));
        assertEquals("b", k.get("b"));
        assertEquals("c", k.get("c"));

        k.put(null, "null");
        assertEquals("null", k.get(null));
        assertEquals("a", k.get("a"));
        assertEquals("b", k.get("b"));
        assertEquals("c", k.get("c"));


        k.put(null, "NULL");
        assertEquals("NULL", k.get(null));
        assertEquals("a", k.get("a"));
        assertEquals("b", k.get("b"));
        assertEquals("c", k.get("c"));

        k.remove(null);
        assertNull(k.get(null));
        assertFalse(k.containsKey(null));
        assertEquals("a", k.get("a"));
        assertEquals("b", k.get("b"));
        assertEquals("c", k.get("c"));
    }

}
