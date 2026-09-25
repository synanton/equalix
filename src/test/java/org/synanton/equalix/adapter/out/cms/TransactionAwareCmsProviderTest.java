package org.synanton.equalix.adapter.out.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.synanton.equalix.domain.port.out.CMSProviderPort;

@ExtendWith(MockitoExtension.class)
class TransactionAwareCmsProviderTest {

    @Mock
    private CMSProviderPort delegate;

    @InjectMocks
    private TransactionAwareCmsProvider provider;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldApplyImmediatelyOutsideTransaction() {
        provider.add("tenantA", 1);

        verify(delegate).add("tenantA", 1);
    }

    @Test
    void shouldBufferInsideTransactionAndApplyNetDeltasAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        provider.add("tenantA", 1);
        provider.add("tenantA", 1);
        provider.add("tenantB", 1);
        provider.add("tenantB", -1);
        verifyNoInteractions(delegate);

        complete(TransactionSynchronization.STATUS_COMMITTED);

        verify(delegate).add("tenantA", 2);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void shouldDiscardBufferedDeltasOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        provider.add("tenantA", 1);

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(delegate);
    }

    @Test
    void shouldRegisterOneSynchronizationPerTransaction() {
        TransactionSynchronizationManager.initSynchronization();

        provider.add("tenantA", 1);
        provider.add("tenantB", 1);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void shouldKeepSuspendedOuterTransactionBufferSeparate() {
        TransactionSynchronizationManager.initSynchronization();
        provider.add("outer", 1);
        // Suspend the outer transaction as REQUIRES_NEW does, run an inner one, then resume.
        List<TransactionSynchronization> suspended = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        provider.add("inner", 1);
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(delegate).add("inner", 1);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        suspended.forEach(TransactionSynchronizationManager::registerSynchronization);

        complete(TransactionSynchronization.STATUS_COMMITTED);

        verify(delegate).add("outer", 1);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void shouldNotFailCommittedTransactionWhenSketchUpdateFails() {
        doThrow(new IllegalStateException("redis down")).when(delegate).add("tenantA", 1);
        TransactionSynchronizationManager.initSynchronization();
        provider.add("tenantA", 1);
        provider.add("tenantB", 1);

        complete(TransactionSynchronization.STATUS_COMMITTED);

        verify(delegate).add("tenantB", 1);
    }

    @Test
    void shouldDelegateReadsAndRebuild() {
        when(delegate.estimateCount("tenantA")).thenReturn(4L);
        when(delegate.totalInFlight()).thenReturn(9L);

        provider.rebuild(Map.of("tenantA", 4));

        assertThat(List.of(provider.estimateCount("tenantA"), provider.totalInFlight())).containsExactly(4L, 9L);
        verify(delegate).rebuild(Map.of("tenantA", 4));
    }

    /** Runs the synchronization callbacks the way a transaction manager does at the end of a transaction. */
    private static void complete(int status) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        if (status == TransactionSynchronization.STATUS_COMMITTED) {
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        }
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
    }
}
