"use client";

import { Flex } from "antd";
import { useRef, type ReactNode } from "react";
import { useQueryFilterSpan } from "@/platform/components/use-query-filter-span";

export function AssignmentSearchLayout({ children, hint, actions }: {
  children: ReactNode;
  hint?: ReactNode;
  actions: ReactNode;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const span = useQueryFilterSpan(containerRef);
  const columnCount = 24 / span;

  return <div className="assignment-search" ref={containerRef} role="search">
    {hint && <div className="assignment-search-hint">{hint}</div>}
    <div className="assignment-search-fields" style={{ gridTemplateColumns: `repeat(${columnCount}, minmax(0, 1fr))` }}>
      {children}
    </div>
    <Flex className="assignment-search-actions" wrap justify="flex-end" gap={8}>{actions}</Flex>
  </div>;
}

export function AssignmentSearchField({ label, inputId, children }: {
  label: string;
  inputId: string;
  children: ReactNode;
}) {
  return <div className="assignment-search-field">
    <label id={`${inputId}-label`} className="assignment-search-field-label" htmlFor={inputId}>{label}</label>
    <div className="assignment-search-field-control">{children}</div>
  </div>;
}
