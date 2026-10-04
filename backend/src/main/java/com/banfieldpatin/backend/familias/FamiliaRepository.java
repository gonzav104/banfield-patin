package com.banfieldpatin.backend.familias;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FamiliaRepository extends JpaRepository<Familia, UUID> {

	Optional<Familia> findByIdAndEscuelaId(UUID id, UUID escuelaId);
}
