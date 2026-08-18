package org.synanton.equalix.domain.port.out;

import java.util.List;
import java.util.Optional;
import org.synanton.equalix.domain.model.ClientSequenceState;

/** Outgoing port for persisting sequential execution state per client. */
public interface ClientSequenceStateRepositoryPort {

    /** Returns existing state or creates a new default state for the given fairness key. */
    ClientSequenceState findOrCreate(String fairnessKey);

    Optional<ClientSequenceState> findByFairnessKey(String fairnessKey);

    ClientSequenceState save(ClientSequenceState state);

    /** Returns clients that have no task currently executing and are not blocked. */
    List<ClientSequenceState> findReadyClients();

    List<ClientSequenceState> findBlockedClients();
}
