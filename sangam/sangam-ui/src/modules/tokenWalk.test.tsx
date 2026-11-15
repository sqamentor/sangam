// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Round 6, modeller token walk (modules/platform.tsx TokenWalk) against POST /models/token-walk (walkModelToken,
 * ModelTokenWalkRequest.choices; ModellerController.TokenWalkView): every move shows how the token got there (via FLOW,
 * BOUNDARY, EVENT_SUB_PROCESS or COMPENSATION); at an activity with boundary events the user completes it or fires one
 * of them; an event sub-process is triggered by its start event id at a node the token reached. The choices map,
 * keyed by element id with the values in order for repeated visits, is the request; the API client is mocked.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';

const api = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, post: api.post } };
});
vi.mock('../components/BpmnDiagram', () => ({ BpmnDiagram: (p: { selected?: string[] }) => <div data-testid="bpmn" data-selected={(p.selected ?? []).join(',')} /> }));

import { initI18n } from '../i18n';
import { L, T, problem, show } from '../test/screens';
import { TokenWalk, eventSubProcessStarts } from './platform';

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d">
  <bpmn:process id="grievance" isExecutable="true">
    <bpmn:startEvent id="Start" />
    <bpmn:userTask id="Task_Review" name="Review" />
    <bpmn:boundaryEvent id="Timer_Late" attachedToRef="Task_Review" />
    <bpmn:subProcess id="Esp_Withdraw" triggeredByEvent="true">
      <bpmn:startEvent id="Start_Withdraw" name="Applicant withdraws" />
    </bpmn:subProcess>
  </bpmn:process>
</bpmn:definitions>`;

const view = (o: Record<string, unknown>) => ({ status: 'COMPLETED', moves: [], visited: [], warnings: [], current: [], choices: [], done: true, state: {}, ...o });

beforeAll(() => initI18n('en'));
beforeEach(() => api.post.mockReset());
afterEach(cleanup);

const walk = () => show(<TokenWalk file="grievance.bpmn" bpmnXml={XML} />);
const lastBody = () => api.post.mock.calls[api.post.mock.calls.length - 1][1] as Record<string, unknown>;

describe('token walk', () => {
  it('each move names how the token got there', async () => {
    api.post.mockResolvedValueOnce(
      view({
        moves: [
          { step: 1, flowId: 'F1', fromNode: 'Start', toNode: 'Task_Review', via: 'FLOW' },
          { step: 2, fromNode: 'Task_Review', toNode: 'Timer_Late', via: 'BOUNDARY' },
          { step: 3, fromNode: 'Task_Review', toNode: 'Start_Withdraw', via: 'EVENT_SUB_PROCESS' },
          { step: 4, flowId: 'A1', fromNode: 'Comp_1', toNode: 'Undo_Fee', via: 'COMPENSATION' },
        ],
        visited: ['Start', 'Task_Review', 'Timer_Late', 'Start_Withdraw', 'Undo_Fee'],
        current: ['Undo_Fee'],
      }),
    );
    walk();
    fireEvent.click(screen.getByRole('button', { name: T('act.step') }));
    const moves = await screen.findByRole('list', { name: T('mod.modeller2.moves') });
    const items = within(moves).getAllByRole('listitem').map((li) => li.textContent);
    expect(items).toEqual([
      `Start → Task_Review · ${T('mod.modeller2.via.FLOW')} F1`,
      `Task_Review → Timer_Late · ${T('mod.modeller2.via.BOUNDARY')}`,
      `Task_Review → Start_Withdraw · ${T('mod.modeller2.via.EVENT_SUB_PROCESS')}`,
      `Comp_1 → Undo_Fee · ${T('mod.modeller2.via.COMPENSATION')} A1`,
    ]);
    expect(lastBody()).toEqual({ file: 'grievance.bpmn', xml: XML, choices: {} });
    expect(screen.getByTestId('bpmn').getAttribute('data-selected')).toBe('Undo_Fee');
  });

  it('at a gateway the user picks the outgoing flow; it is sent in the choices map under the gateway id', async () => {
    api.post.mockResolvedValueOnce(view({ status: 'NEEDS_CHOICE', done: false, pending: { gatewayId: 'Gw_Fee', options: ['Flow_Pay', 'Flow_Waive'] }, current: ['Gw_Fee'] }));
    api.post.mockResolvedValueOnce(view({}));
    walk();
    fireEvent.click(screen.getByRole('button', { name: T('act.step') }));
    const g = await screen.findByRole('group', { name: T('mod.modeller2.gateway') });
    fireEvent.click(within(g).getByRole('button', { name: 'Flow_Waive' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(2));
    expect(lastBody()).toEqual({ file: 'grievance.bpmn', xml: XML, choices: { Gw_Fee: ['Flow_Waive'] } });
  });

  it('at an activity with boundary events the user completes it or fires one; repeated visits keep their order', async () => {
    const pending = { status: 'NEEDS_CHOICE', done: false, pending: { gatewayId: 'Task_Review', options: ['Task_Review', 'Timer_Late'] }, current: ['Task_Review'] };
    api.post.mockResolvedValueOnce(view(pending)).mockResolvedValueOnce(view(pending)).mockResolvedValueOnce(view({}));
    walk();
    fireEvent.click(screen.getByRole('button', { name: T('act.step') }));
    let g = await screen.findByRole('group', { name: T('mod.modeller2.boundaryChoice', { id: 'Task_Review' }) });
    expect(screen.queryByRole('group', { name: T('mod.modeller2.gateway') })).toBeNull();
    fireEvent.click(within(g).getByRole('button', { name: T('mod.modeller2.fireBoundary', { id: 'Timer_Late' }) }));
    await waitFor(() => expect(lastBody()).toEqual({ file: 'grievance.bpmn', xml: XML, choices: { Task_Review: ['Timer_Late'] } }));
    g = await screen.findByRole('group', { name: T('mod.modeller2.boundaryChoice', { id: 'Task_Review' }) });
    fireEvent.click(within(g).getByRole('button', { name: T('mod.modeller2.completeActivity', { id: 'Task_Review' }) }));
    await waitFor(() => expect(lastBody()).toEqual({ file: 'grievance.bpmn', xml: XML, choices: { Task_Review: ['Timer_Late', 'Task_Review'] } }));
  });

  it('an event sub-process is triggered by its start event id at a node the token reached', async () => {
    api.post.mockResolvedValueOnce(view({ status: 'NEEDS_CHOICE', done: false, pending: { gatewayId: 'Task_Review', options: ['Task_Review', 'Timer_Late'] }, visited: ['Start', 'Task_Review'], current: ['Task_Review'] }));
    api.post.mockResolvedValueOnce(view({ moves: [{ step: 2, fromNode: 'Task_Review', toNode: 'Start_Withdraw', via: 'EVENT_SUB_PROCESS' }] }));
    walk();
    fireEvent.click(screen.getByRole('button', { name: T('act.step') }));
    const g = await screen.findByRole('group', { name: T('mod.modeller2.triggerEsp') });
    expect((within(g).getByLabelText(L('mod.modeller2.espStart')) as HTMLSelectElement).value).toBe('Start_Withdraw');
    fireEvent.change(within(g).getByLabelText(L('mod.modeller2.espAt')), { target: { value: 'Task_Review' } });
    fireEvent.click(within(g).getByRole('button', { name: T('mod.modeller2.trigger') }));
    await waitFor(() => expect(lastBody()).toEqual({ file: 'grievance.bpmn', xml: XML, choices: { Start_Withdraw: ['Task_Review'] } }));
    expect(await screen.findByText(`Task_Review → Start_Withdraw · ${T('mod.modeller2.via.EVENT_SUB_PROCESS')}`)).toBeTruthy();
  });

  it('a choice the model does not have (422 MODELLER_REJECTED) is shown by name', async () => {
    api.post.mockRejectedValueOnce(problem(422, 'MODELLER_REJECTED', 'Gw_Fee has no outgoing flow Flow_X'));
    walk();
    fireEvent.click(screen.getByRole('button', { name: T('act.step') }));
    expect(await screen.findByText(T('error.validation'))).toBeTruthy();
    expect(screen.getByText('Gw_Fee has no outgoing flow Flow_X')).toBeTruthy();
  });

  it('clear forgets every choice', async () => {
    api.post.mockResolvedValueOnce(view({ status: 'NEEDS_CHOICE', done: false, pending: { gatewayId: 'Gw_Fee', options: ['Flow_Pay'] } })).mockResolvedValue(view({}));
    walk();
    fireEvent.click(screen.getByRole('button', { name: T('act.step') }));
    fireEvent.click(within(await screen.findByRole('group', { name: T('mod.modeller2.gateway') })).getByRole('button', { name: 'Flow_Pay' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByRole('button', { name: T('common.clear') }));
    fireEvent.click(screen.getByRole('button', { name: T('act.step') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(3));
    expect(lastBody()).toEqual({ file: 'grievance.bpmn', xml: XML, choices: {} });
  });

  it('the event sub-process start events are read from the model', () => {
    expect(eventSubProcessStarts(XML)).toEqual([{ id: 'Start_Withdraw', name: 'Applicant withdraws' }]);
    expect(eventSubProcessStarts('<not xml')).toEqual([]);
  });
});
