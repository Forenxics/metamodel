/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.metamodel.util;

import junit.framework.TestCase;

public class WildcardPatternTest extends TestCase {

	public void testMatches() throws Exception {
		WildcardPattern pattern = new WildcardPattern("foo%bar", '%');
		assertTrue(pattern.matches("foobar"));
		assertTrue(pattern.matches("foofoobar"));
		// the wildcard may match any sequence of characters - including
		// "bar" - just like in a SQL LIKE clause
		assertTrue(pattern.matches("foobarbar"));
		assertFalse(pattern.matches("w00p"));

		pattern = new WildcardPattern("*foo*bar", '*');
		assertTrue(pattern.matches("foobar"));
		assertTrue(pattern.matches("foofoobar"));
		assertTrue(pattern.matches("foobarbar"));
		assertFalse(pattern.matches("w00p"));

		pattern = new WildcardPattern("foo%bar%", '%');
		assertTrue(pattern.matches("foobar"));
		assertTrue(pattern.matches("foofoobar"));
		assertTrue(pattern.matches("foobarbar"));
		assertFalse(pattern.matches("w00p"));

		pattern = new WildcardPattern("oba%", '%');
		assertTrue(pattern.matches("obar"));
		assertFalse(pattern.matches("foobar"));

		pattern = new WildcardPattern("bar", '%');
		assertTrue(pattern.matches("bar"));
		assertFalse(pattern.matches("foobar"));

		pattern = new WildcardPattern("", '%');
		assertTrue(pattern.matches(""));
		assertFalse(pattern.matches("foo"));
	}

	/**
	 * The '_' character must be treated as the SQL single character wildcard,
	 * so that a LIKE filter produces the same result in-memory as it does
	 * when it is executed as SQL by a datastore.
	 */
	public void testSingleCharacterWildcard() throws Exception {
		WildcardPattern pattern = new WildcardPattern("a_c", '%');
		assertTrue(pattern.matches("abc"));
		assertTrue(pattern.matches("a_c"));
		assertFalse(pattern.matches("ac"));
		assertFalse(pattern.matches("abbc"));

		pattern = new WildcardPattern("%_", '%');
		assertTrue(pattern.matches("a"));
		assertFalse(pattern.matches(""));
		// '%_' matches any string with at least one character
		assertTrue(pattern.matches("ab"));
	}

	public void testWildcardMatchesEmptySequence() throws Exception {
		WildcardPattern pattern = new WildcardPattern("foo%", '%');
		assertTrue(pattern.matches("foo"));

		pattern = new WildcardPattern("%foo", '%');
		assertTrue(pattern.matches("foo"));

		pattern = new WildcardPattern("%", '%');
		assertTrue(pattern.matches(""));
		assertTrue(pattern.matches("anything"));
	}

	public void testNullValueNeverMatches() throws Exception {
		WildcardPattern pattern = new WildcardPattern("foo%", '%');
		assertFalse(pattern.matches(null));
	}

	public void testPatternCannotBeNull() throws Exception {
		try {
			new WildcardPattern(null, '%');
			fail("Exception should have been thrown");
		} catch (IllegalArgumentException e) {
			assertEquals("Pattern cannot be null", e.getMessage());
		}
	}
}
