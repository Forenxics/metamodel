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

import java.io.Serializable;

import org.apache.metamodel.query.FilterItem;

/**
 * Represents a pattern with a wildcard character. These are typically used in
 * FilterItems with the LIKE operator.
 * <p>
 * The matching semantics are those of the SQL <code>LIKE</code> operator:
 * <ul>
 * <li>the wildcard character (typically '%') matches any sequence of
 * characters, including the empty sequence</li>
 * <li>the single character wildcard '_' matches exactly one character</li>
 * </ul>
 * This makes the in-memory evaluation of a LIKE filter produce the same
 * result as the SQL that is generated for datastores which are able to
 * execute the filter themselves.
 *
 * @see FilterItem
 */
public final class WildcardPattern implements Serializable {

	private static final long serialVersionUID = 857462137797209624L;

	/**
	 * The character that matches any sequence of characters.
	 */
	private final char _wildcard;

	/**
	 * The character that matches exactly one character.
	 */
	private final char _singleCharWildcard;

	private final String _pattern;

	public WildcardPattern(String pattern, char wildcard) {
		if (pattern == null) {
			throw new IllegalArgumentException("Pattern cannot be null");
		}
		_pattern = pattern;
		_wildcard = wildcard;
		_singleCharWildcard = '_';
	}

	public boolean matches(String value) {
		if (value == null) {
			return false;
		}
		return matches(_pattern, value);
	}

	/**
	 * Matches a value against a pattern using the semantics of the SQL
	 * <code>LIKE</code> operator. The algorithm keeps track of the last
	 * position at which the wildcard was encountered, so that it can be
	 * expanded by one character at a time whenever a subsequent part of the
	 * pattern does not match. This keeps the matching linear in the size of
	 * the pattern and the value (as opposed to a naive recursive
	 * implementation, which can degrade exponentially).
	 *
	 * @param pattern
	 * @param value
	 * @return true if the value matches the pattern
	 */
	private boolean matches(String pattern, String value) {
		int patternIndex = 0;
		int valueIndex = 0;
		int wildcardIndex = -1;
		int wildcardValueIndex = -1;
		boolean firstEntry = true;

		while (valueIndex < value.length()) {
			if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == _wildcard) {
				// remember where the wildcard was encountered, so that it can
				// be expanded further if the rest of the pattern does not match
				wildcardIndex = patternIndex;
				wildcardValueIndex = valueIndex;
				patternIndex++;
			} else if (patternIndex < pattern.length()
					&& (pattern.charAt(patternIndex) == _singleCharWildcard
							|| pattern.charAt(patternIndex) == value.charAt(valueIndex))) {
				patternIndex++;
				valueIndex++;
			} else if (wildcardIndex != -1) {
				// let the wildcard consume one more character and retry
				wildcardValueIndex++;
				valueIndex = wildcardValueIndex;
				patternIndex = wildcardIndex + 1;
			} else {
				return false;
			}
			firstEntry = false;
		}

		// the value must be fully consumed, except for trailing wildcards
		// which are allowed to match the empty sequence
		while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == _wildcard) {
			patternIndex++;
		}
		return patternIndex == pattern.length();
	}
}
