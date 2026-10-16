package sk.synoAdmin.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class QuickActionsTest {

	@Test
	void photosFolderUrl_espacePartage() {
		assertEquals("https://nas:5001/photo/#/shared_space/folder/42", QuickActions.photosFolderUrl("https://nas:5001", 42));
		assertEquals("https://nas/photo/#/shared_space/folder/7", QuickActions.photosFolderUrl("https://nas/", 7));
	}
}
