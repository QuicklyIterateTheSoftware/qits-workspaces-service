package eu.wohlben.qits.workspaces.persistence;

import eu.wohlben.qits.workspaces.entity.LegacyPlacement;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** The rows a retired workspaces runner held ({@code V30}). */
@ApplicationScoped
public class LegacyPlacementRepository implements PanacheRepositoryBase<LegacyPlacement, Long> {}
