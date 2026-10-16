package sk.synoAdmin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sk.synoAdmin.UserException;

class MenuServiceTest {

	@Test
	void installBoth_echecWindows11_menuClassiqueDejaInstalle() {
		final List<String> calls = new ArrayList<>();

		final UserException e = assertThrows(UserException.class, () -> MenuService.installBoth(
				() -> calls.add("classique"),
				() -> {
					calls.add("modern");
					throw new UserException("autorisation refusée");
				}));

		assertEquals(List.of("classique", "modern"), calls);
		assertTrue(e.getMessage().contains("Classic menu installed"));
		assertTrue(e.getMessage().contains("autorisation refusée"));
	}
}
