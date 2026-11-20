package com.example.customer_retail_system.system.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CatalogRepositoryTest {

	@Autowired
	CategoryRepository categories;

	@Autowired
	ProductRepository products;

	@Autowired
	TestEntityManager em;

	@Test
	void savesAndFindsCategoryWithPolicy() {
		categories.save(new Category("T_ELEC", "Test electronics", true, 10));
		categories.save(new Category("T_INNER", "Test innerwear", false, null));
		em.flush();
		em.clear();

		Category elec = categories.findByCode("T_ELEC").orElseThrow();
		assertThat(elec.isReturnable()).isTrue();
		assertThat(elec.getReturnWindowDays()).isEqualTo(10);

		Category inner = categories.findByCode("T_INNER").orElseThrow();
		assertThat(inner.isReturnable()).isFalse();
		assertThat(inner.getReturnWindowDays()).isNull();
	}

	@Test
	void savesProductAndLoadsCategoryEagerlyWhenAsked() {
		Category cat = categories.save(new Category("T_HOME", "Test home", true, 30));
		Product p = products.save(new Product("T-HOME-1", "Kettle", cat, 1_299_00));
		em.flush();
		em.clear();

		assertThat(products.findBySku("T-HOME-1")).get()
				.satisfies(found -> assertThat(found.getPricePaise()).isEqualTo(129_900));

		List<Product> loaded = products.findByIdIn(List.of(p.getId()));
		em.clear(); // detach: category must already be initialised by the entity graph
		assertThat(loaded).singleElement()
				.satisfies(found -> assertThat(found.getCategory().getCode()).isEqualTo("T_HOME"));
	}

	@Test
	void skuIsUnique() {
		Category cat = categories.save(new Category("T_APP", "Test apparel", true, 30));
		products.saveAndFlush(new Product("T-DUP", "Shirt", cat, 999_00));
		assertThatThrownBy(() -> products.saveAndFlush(new Product("T-DUP", "Shirt again", cat, 999_00)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void negativePriceIsRejected() {
		Category cat = categories.save(new Category("T_BEAU", "Test beauty", true, 30));
		assertThatThrownBy(() -> products.saveAndFlush(new Product("T-NEG", "Bad", cat, -1)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
