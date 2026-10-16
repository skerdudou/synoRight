package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CloseGuardTest {

	@Test
	void fermetureLibreSansEcriture() {
		final CloseGuard guard = new CloseGuard();
		final AtomicInteger busy = new AtomicInteger();
		final AtomicInteger free = new AtomicInteger();
		assertTrue(guard.requestClose(busy::incrementAndGet, free::incrementAndGet));
		assertEquals(0, busy.get());
		assertEquals(1, free.get());
	}

	@Test
	void fermetureRefuseePendantUneEcriture() {
		final CloseGuard guard = new CloseGuard();
		final AtomicInteger busy = new AtomicInteger();
		final AtomicInteger free = new AtomicInteger();
		guard.begin();
		assertTrue(guard.isBusy());
		assertFalse(guard.requestClose(busy::incrementAndGet, free::incrementAndGet));
		assertEquals(1, busy.get());
		assertEquals(0, free.get());
		guard.end();
		assertFalse(guard.isBusy());
		assertTrue(guard.requestClose(busy::incrementAndGet, free::incrementAndGet));
	}

	@Test
	void ecrituresImbriqueesEtFinSansDebut() {
		final CloseGuard guard = new CloseGuard();
		guard.end();
		assertFalse(guard.isBusy());
		guard.begin();
		guard.begin();
		guard.end();
		assertTrue(guard.isBusy());
		guard.end();
		assertFalse(guard.isBusy());
	}
}
