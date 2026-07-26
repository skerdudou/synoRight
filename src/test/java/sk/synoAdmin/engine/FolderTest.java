package sk.synoAdmin.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;

import org.junit.jupiter.api.Test;

class FolderTest {

	@Test
	void nom_dossierRacine() {
		final Folder racine = new Folder(new File("vacances"), null);

		assertEquals("/vacances", racine.getName());
	}

	@Test
	void nom_sousDossier_prefixeParLeParent() {
		final Folder racine = new Folder(new File("vacances"), null);
		final Folder sousDossier = new Folder(new File("2024"), racine);

		assertEquals("/vacances/2024", sousDossier.getName());
	}

	@Test
	void setId_memeValeur_ok() {
		final Folder folder = new Folder(new File("vacances"), null);
		folder.setId(42);
		folder.setId(42);

		assertEquals(42, folder.getId());
	}

	@Test
	void setId_valeurDifferente_exception() {
		final Folder folder = new Folder(new File("vacances"), null);
		folder.setId(42);

		assertThrows(RuntimeException.class, () -> folder.setId(43));
	}
}
