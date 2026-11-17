package com.hospio.payment.service;

import com.hospio.common.enums.Role;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.PosOfflineEvent;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.dto.TerminalRequest;
import com.hospio.payment.entity.PosRoleAssignment;
import com.hospio.payment.entity.PosTerminal;
import com.hospio.payment.repository.PosRoleAssignmentRepository;
import com.hospio.payment.repository.PosTerminalRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Hotel setup 20.2-20.5: terminals and which role uses which. A role has exactly one POS. */
@Service
@RequiredArgsConstructor
public class TerminalService {

    private final PosTerminalRepository terminals;
    private final PosRoleAssignmentRepository assignments;
    private final EventPublisher events;

    @Transactional(readOnly = true)
    public List<PosTerminal> list() {
        return terminals.findByHotelIdOrderByName(TenantContext.requireHotel());
    }

    @Transactional(readOnly = true)
    public List<PosRoleAssignment> assignments() {
        return assignments.findByHotelId(TenantContext.requireHotel());
    }

    @Transactional
    public PosTerminal save(String id, TerminalRequest request) {
        PosTerminal t = id == null ? new PosTerminal() : find(id);
        t.setName(request.name());
        t.setProvider(request.provider());
        t.setTerminalRef(request.terminalRef());
        return terminals.save(t);
    }

    @Transactional
    public PosRoleAssignment assign(String terminalId, Role role) {
        PosTerminal t = find(terminalId);
        PosRoleAssignment a = assignments.findByHotelIdAndRole(t.getHotelId(), role).orElseGet(() -> PosRoleAssignment.builder().role(role).build());
        a.setTerminalId(t.getId());
        return assignments.save(a);
    }

    @Transactional
    public void retire(String terminalId) {
        PosTerminal t = find(terminalId);
        assignments.findByTerminalId(terminalId).forEach(assignments::delete);
        t.setStatus(PosTerminal.Status.RETIRED);
        terminals.save(t);
    }

    @Transactional(readOnly = true)
    public Optional<PosTerminal> forRole(String hotelId, Role role) {
        return assignments.findByHotelIdAndRole(hotelId, role).flatMap(a -> terminals.findById(a.getTerminalId()));
    }

    @Transactional
    public void markOffline(PosTerminal t, Role role) {
        if (t.getStatus() == PosTerminal.Status.OFFLINE) {
            return;
        }
        t.setStatus(PosTerminal.Status.OFFLINE);
        terminals.save(t);
        events.publish(KafkaTopics.POS_OFFLINE, PosOfflineEvent.builder().hotelId(t.getHotelId()).terminalId(t.getId())
                .terminalName(t.getName()).role(role == null ? null : role.name()).build());
    }

    @Transactional
    public void markOnline(PosTerminal t) {
        t.setStatus(PosTerminal.Status.ONLINE);
        t.setLastSeenAt(Instant.now());
        terminals.save(t);
    }

    @Transactional
    public PosTerminal setStatus(String terminalId, PosTerminal.Status status) {
        PosTerminal t = find(terminalId);
        if (status == PosTerminal.Status.OFFLINE) {
            markOffline(t, null);
        } else {
            t.setStatus(status);
            terminals.save(t);
        }
        return t;
    }

    /** A terminal's name, or null when there's no terminal (cash) or it was removed. */
    public String nameOf(String terminalId) {
        return terminalId == null ? null : terminals.findById(terminalId).map(PosTerminal::getName).orElse(null);
    }

    /** Names of a hotel's terminals by id, for lists. */
    public java.util.Map<String, String> namesFor(java.util.Collection<String> terminalIds) {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        terminals.findAllById(terminalIds.stream().filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(t -> names.put(t.getId(), t.getName()));
        return names;
    }

    /** The terminal the desk picked, when it is one of this hotel's. */
    public Optional<PosTerminal> picked(String hotelId, String terminalId) {
        return terminals.findById(terminalId).filter(t -> hotelId.equals(t.getHotelId()));
    }

    PosTerminal find(String id) {
        return terminals.findById(id).orElseThrow(() -> new ResourceNotFoundException("Terminal not found"));
    }
}
