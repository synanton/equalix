package org.synanton.equalix.config.properties;

import lombok.Data;

/** Configuration for persistent weighted virtual time (T_k). */
@Data
public class VirtualTimeProperties {

    /**
     * Virtual-time units charged for one unit-cost task at weight 1.0; a task advances its key by
     * {@code quantum * cost / weight}. Sized relative to the in-flight pressure term, which is expressed in
     * milliseconds ({@code 1000 / currentRps} per in-flight task).
     */
    private double quantum;
}
