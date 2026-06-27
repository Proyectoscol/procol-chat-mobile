import type { RootState } from '@/store';
import { inboxAdapter } from './inboxSlice';
import { createSelector } from '@reduxjs/toolkit';

export const selectInboxesState = (state: RootState) => state.inboxes;

export const { selectAll: selectAllInboxes } =
  inboxAdapter.getSelectors<RootState>(selectInboxesState);

export const selectInboxById = createSelector(
  [selectAllInboxes, (_state: RootState, inboxId: number) => inboxId],
  (inboxes, inboxId) => inboxes.find(inbox => inbox.id === inboxId),
);
