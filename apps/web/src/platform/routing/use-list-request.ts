"use client";

import { useState } from "react";
import { createListRequest, type ListRequest } from "../api/pagination";

/** Keep local list state consistent without duplicating paging defaults in features. */
export function useListRequest(overrides: Partial<ListRequest> = {}) {
  return useState<ListRequest>(() => createListRequest(overrides));
}
