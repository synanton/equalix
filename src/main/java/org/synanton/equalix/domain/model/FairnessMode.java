package org.synanton.equalix.domain.model;

/** How fairness keys are scheduled against each other. */
public enum FairnessMode {

    /** Every fairness key is an independent tenant (EQX-3 virtual time). */
    FLAT,

    /**
     * Fairness keys are paths ({@code org/department/...}); each layer of the tree is scheduled fairly among
     * its siblings (EQX-7).
     */
    HIERARCHICAL
}
