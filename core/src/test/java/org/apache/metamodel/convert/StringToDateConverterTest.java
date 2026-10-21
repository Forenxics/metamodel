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
package org.apache.metamodel.convert;

import java.text.DateFormat;
import java.text.FieldPosition;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.TestCase;

import org.apache.metamodel.util.DateUtils;
import org.apache.metamodel.util.Month;

public class StringToDateConverterTest extends TestCase {

	/**
	 * The physical representation that the converter produces must be parsable
	 * back into the very same date. Before the fix, the default converter
	 * serialized a date using the MEDIUM format, but only knew how to parse
	 * numeric date patterns - so a round trip silently produced null.
	 */
	public void testRoundTripWithNoArgsConverter() throws Exception {
		StringToDateConverter conv = new StringToDateConverter();
		Date input = DateUtils.get(2010, Month.DECEMBER, 31);
		String physicalValue = conv.toPhysicalValue(input);
		assertNotNull(physicalValue);
		Date virtualValue = conv.toVirtualValue(physicalValue);
		assertNotNull("the physical value [" + physicalValue + "] must be parsable back into a date",
				virtualValue);
		assertEquals(input, virtualValue);
	}

	public void testRoundTripWithPatternConverter() throws Exception {
		StringToDateConverter conv = new StringToDateConverter("yyyy-MM-dd HH:mm:ss");
		Date input = DateUtils.get(2010, Month.DECEMBER, 31);
		String physicalValue = conv.toPhysicalValue(input);
		assertEquals("2010-12-31 00:00:00", physicalValue);
		assertEquals(input, conv.toVirtualValue(physicalValue));
	}

	/**
	 * A date pattern that contains a month name must be interpretable
	 * independently of the JVM's default locale.
	 */
	public void testExplicitLocale() throws Exception {
		Locale defaultLocale = Locale.getDefault();
		try {
			Locale.setDefault(Locale.FRANCE);

			StringToDateConverter conv = new StringToDateConverter("MMMM d, yyyy", Locale.US);
			Date expected = DateUtils.get(2010, Month.DECEMBER, 31);
			assertEquals(expected, conv.toVirtualValue("December 31, 2010"));
			assertEquals("December 31, 2010", conv.toPhysicalValue(expected));

			// without an explicit locale the pattern follows the JVM's default
			// locale, which cannot parse the English month name
			StringToDateConverter defaultConv = new StringToDateConverter("MMMM d, yyyy");
			try {
				defaultConv.toVirtualValue("December 31, 2010");
				fail("Exception should have been thrown");
			} catch (IllegalArgumentException e) {
				// expected - the default locale does not know "December"
			}
		} finally {
			Locale.setDefault(defaultLocale);
		}
	}

	/**
	 * The converter must not use the (non thread safe) {@link DateFormat} it
	 * was constructed with from more than one thread at a time.
	 */
	public void testConverterIsThreadSafe() throws Exception {
		final int threadCount = 4;
		// the DateFormat must never be used by more than one thread at a time,
		// so any overlap between two threads is already a violation
		final ConcurrencyDetectingDateFormat dateFormat = new ConcurrencyDetectingDateFormat(2);
		final StringToDateConverter conv = new StringToDateConverter(dateFormat);
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch doneLatch = new CountDownLatch(threadCount);
		final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());

		for (int i = 0; i < threadCount; i++) {
			Thread thread = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						startLatch.await();
						conv.toVirtualValue("2010-12-31");
						conv.toPhysicalValue(DateUtils.get(2010, Month.DECEMBER, 31));
					} catch (Throwable e) {
						errors.add(e);
					} finally {
						doneLatch.countDown();
					}
				}
			});
			thread.setDaemon(true);
			thread.start();
		}
		startLatch.countDown();

		assertTrue("threads should have finished", doneLatch.await(60, TimeUnit.SECONDS));
		assertEquals("no errors should have occurred: " + errors, 0, errors.size());
		assertFalse("the DateFormat must not be used by more than one thread at a time",
				dateFormat.isConcurrencyViolationDetected());
	}

	/**
	 * A {@link DateFormat} which detects whether it is being used by more than
	 * one thread at the same time.
	 */
	private static class ConcurrencyDetectingDateFormat extends DateFormat {

		private static final long serialVersionUID = 1L;

		private final SimpleDateFormat _delegate = new SimpleDateFormat("yyyy-MM-dd");
		private final AtomicInteger _inFlight = new AtomicInteger();
		private final AtomicBoolean _violation = new AtomicBoolean();
		private final AtomicBoolean _firstEntry = new AtomicBoolean(true);
		private final CountDownLatch _latch = new CountDownLatch(1);
		private final int _expectedConcurrency;

		public ConcurrencyDetectingDateFormat(int expectedConcurrency) {
			_expectedConcurrency = expectedConcurrency;
		}

		@Override
		public StringBuffer format(Date date, StringBuffer toAppendTo, FieldPosition pos) {
			enter();
			try {
				return _delegate.format(date, toAppendTo, pos);
			} finally {
				leave();
			}
		}

		@Override
		public Date parse(String source, ParsePosition pos) {
			enter();
			try {
				return _delegate.parse(source, pos);
			} finally {
				leave();
			}
		}

		private void enter() {
			int concurrent = _inFlight.incrementAndGet();
			if (concurrent >= _expectedConcurrency) {
				_violation.set(true);
				_latch.countDown();
			} else if (concurrent == 1 && _firstEntry.compareAndSet(true, false)) {
				// give the other threads a chance to enter the format as well
				try {
					_latch.await(2, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}

		private void leave() {
			_inFlight.decrementAndGet();
		}

		public boolean isConcurrencyViolationDetected() {
			return _violation.get();
		}
	}

	public void testToVirtualSimpleDateFormat() throws Exception {
		StringToDateConverter conv = new StringToDateConverter("yyyy-MM-dd");
		assertNull(conv.toVirtualValue(null));
		assertNull(conv.toVirtualValue(""));

		assertEquals(DateUtils.get(2010, Month.DECEMBER, 31),
				conv.toVirtualValue("2010-12-31"));
	}

	public void testToVirtualNoArgs() throws Exception {
		StringToDateConverter conv = new StringToDateConverter();
		assertNull(conv.toVirtualValue(null));
		assertNull(conv.toVirtualValue(""));

		assertEquals(DateUtils.get(2010, Month.DECEMBER, 31),
				conv.toVirtualValue("2010-12-31"));
	}

	public void testToPhysicalSimpleDateFormat() throws Exception {
		StringToDateConverter conv = new StringToDateConverter("yyyy-MM-dd");
		assertNull(conv.toPhysicalValue(null));
		Date input = DateUtils.get(2010, Month.DECEMBER, 31);
		String physicalValue = conv.toPhysicalValue(input);
		assertEquals("2010-12-31", physicalValue);
	}

	public void testToPhysicalNoArgs() throws Exception {
		StringToDateConverter conv = new StringToDateConverter();
		assertNull(conv.toPhysicalValue(null));
		Date input = DateUtils.get(2010, Month.DECEMBER, 31);
		String physicalValue = conv.toPhysicalValue(input);
		Date virtualValue = DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
				DateFormat.MEDIUM).parse(physicalValue);
		assertEquals(virtualValue, input);
	}
}
