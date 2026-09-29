-- Lumira platform schema baseline
-- PostgreSQL 18+ | one immutable initialization script for a new database
--
-- This file is the consolidated end state of the former V1..V48 scripts.
-- It is intentionally ordered as: routines, tables, constraints, indexes,
-- triggers, foreign keys, then reference/module/navigation seeds.
-- Application secrets and bootstrap credentials are never stored here.

SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET search_path = public;
SET check_function_bodies = false;
SET row_security = off;

--
-- Name: platform_file_track_owner(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_file_track_owner() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.owner_id IS NOT NULL THEN
  NEW.orphaned_at=NULL;
 ELSIF TG_OP='INSERT' THEN
  NEW.orphaned_at=clock_timestamp();
 ELSIF OLD.owner_id IS NOT NULL OR NEW.orphaned_at IS NULL THEN
  NEW.orphaned_at=clock_timestamp();
 END IF;
 RETURN NEW;
END $$;


--
-- Name: platform_has_recovery_administrators(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_has_recovery_administrators() RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
 SELECT NOT EXISTS(
  SELECT 1 FROM (VALUES ('platform:users:write'),('platform:roles:write'),('platform:settings:write')) required(capability)
  WHERE NOT EXISTS(SELECT 1 FROM platform_recovery_capability c JOIN platform_user u ON u.id=c.user_id
   WHERE u.enabled AND c.capability=required.capability))
$$;


--
-- Name: platform_is_last_recovery_administrator(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_is_last_recovery_administrator(target uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
 SELECT EXISTS(SELECT 1 FROM platform_recovery_capability own WHERE own.user_id=target
  AND NOT EXISTS(SELECT 1 FROM platform_recovery_capability other JOIN platform_user u ON u.id=other.user_id
   WHERE u.enabled AND u.id<>target AND other.capability=own.capability))
$$;


--
-- Name: platform_visible_users(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.platform_visible_users(actor_name text, permission_name text) RETURNS TABLE(user_id uuid)
    LANGUAGE sql STABLE
    AS $$
 WITH RECURSIVE actor AS (
   SELECT id FROM platform_user WHERE username=actor_name AND enabled
 ), scopes AS (
   SELECT r.data_scope FROM platform_user_role ur JOIN actor a ON a.id=ur.user_id
   JOIN platform_role r ON r.id=ur.role_id AND r.enabled
   JOIN platform_role_authority ra ON ra.role_id=r.id AND ra.authority=permission_name
 ), own_orgs AS (
   SELECT o.id FROM platform_user_organization uo JOIN actor a ON a.id=uo.user_id
   JOIN platform_organization o ON o.id=uo.organization_id AND o.enabled
 ), descendants AS (
   SELECT id FROM own_orgs
   UNION
   SELECT o.id FROM platform_organization o JOIN descendants d ON o.parent_id=d.id WHERE o.enabled
 ), global_scope AS (
   SELECT EXISTS(SELECT 1 FROM platform_authority pa JOIN actor a ON a.id=pa.user_id WHERE pa.authority=permission_name)
     OR EXISTS(SELECT 1 FROM scopes WHERE data_scope='ALL') AS allowed
 )
 SELECT u.id FROM platform_user u WHERE
 (SELECT allowed FROM global_scope)
 OR (u.id IN (SELECT id FROM actor) AND EXISTS(SELECT 1 FROM scopes))
 OR EXISTS(SELECT 1 FROM platform_user_organization member
   WHERE member.user_id=u.id AND (
     (EXISTS(SELECT 1 FROM scopes WHERE data_scope='ORGANIZATION') AND member.organization_id IN (SELECT id FROM own_orgs))
     OR (EXISTS(SELECT 1 FROM scopes WHERE data_scope='SUBTREE') AND member.organization_id IN (SELECT id FROM descendants))
   ));
$$;


-- Keep organization administration on the same data-scope boundary as users.
-- SELF and ORGANIZATION expose the actor's enabled organizations, SUBTREE also
-- exposes their enabled descendants, and ALL (or a direct authority) is global.
CREATE FUNCTION public.platform_visible_organizations(actor_name text, permission_name text) RETURNS TABLE(organization_id uuid)
    LANGUAGE sql STABLE
    AS $$
 WITH RECURSIVE actor AS (
   SELECT id FROM platform_user WHERE username=actor_name AND enabled
 ), scopes AS (
   SELECT r.data_scope FROM platform_user_role ur JOIN actor a ON a.id=ur.user_id
   JOIN platform_role r ON r.id=ur.role_id AND r.enabled
   JOIN platform_role_authority ra ON ra.role_id=r.id AND ra.authority=permission_name
 ), own_orgs AS (
   SELECT o.id FROM platform_user_organization uo JOIN actor a ON a.id=uo.user_id
   JOIN platform_organization o ON o.id=uo.organization_id AND o.enabled
 ), descendants AS (
   SELECT id FROM own_orgs
   UNION
   SELECT o.id FROM platform_organization o JOIN descendants d ON o.parent_id=d.id WHERE o.enabled
 ), global_scope AS (
   SELECT EXISTS(SELECT 1 FROM platform_authority pa JOIN actor a ON a.id=pa.user_id WHERE pa.authority=permission_name)
     OR EXISTS(SELECT 1 FROM scopes WHERE data_scope='ALL') AS allowed
 )
 SELECT o.id
 FROM platform_organization o
 WHERE o.enabled
   AND (
     (SELECT allowed FROM global_scope)
     OR (o.id IN (SELECT id FROM own_orgs) AND EXISTS(SELECT 1 FROM scopes WHERE data_scope IN ('SELF','ORGANIZATION')))
     OR (o.id IN (SELECT id FROM descendants) AND EXISTS(SELECT 1 FROM scopes WHERE data_scope='SUBTREE'))
   );
$$;




--
-- Name: platform_account_draft; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_account_draft (
    id uuid NOT NULL,
    owner_id uuid NOT NULL,
    draft_key character varying(128) NOT NULL,
    revision uuid NOT NULL,
    payload text NOT NULL,
    payload_bytes integer NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_account_draft_check CHECK ((((payload_bytes >= 1) AND (payload_bytes <= 1000000)) AND (payload_bytes = octet_length(payload)))),
    CONSTRAINT platform_account_draft_draft_key_check CHECK (((draft_key)::text ~ '^[a-z0-9][a-z0-9._:-]{0,127}$'::text))
);


--
-- Name: platform_account_email; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_account_email (
    user_id uuid NOT NULL,
    email character varying(254),
    verified_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    pending_email character varying(254),
    token_hash character varying(64),
    expires_at timestamp with time zone,
    requested_at timestamp with time zone,
    request_day date,
    request_count integer DEFAULT 0 NOT NULL,
    failures integer DEFAULT 0 NOT NULL,
    CONSTRAINT platform_account_email_check CHECK (((email IS NULL) = (verified_at IS NULL))),
    CONSTRAINT platform_account_email_check1 CHECK (((pending_email IS NULL) = (token_hash IS NULL))),
    CONSTRAINT platform_account_email_check2 CHECK (((pending_email IS NULL) = (expires_at IS NULL))),
    CONSTRAINT platform_account_email_failures_check CHECK (((failures >= 0) AND (failures <= 5))),
    CONSTRAINT platform_account_email_request_count_check CHECK (((request_count >= 0) AND (request_count <= 10))),
    CONSTRAINT platform_account_email_token_hash_check CHECK (((token_hash IS NULL) OR ((token_hash)::text ~ '^[a-f0-9]{64}$'::text))),
    CONSTRAINT platform_account_email_version_check CHECK ((version >= 0))
);


--
-- Name: platform_account_media; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_account_media (
    user_id uuid NOT NULL,
    avatar_file_id uuid,
    version bigint DEFAULT 0 NOT NULL,
    file_state character varying(20) GENERATED ALWAYS AS ('READY'::character varying) STORED,
    media_type character varying(100) GENERATED ALWAYS AS ('image/png'::character varying) STORED,
    CONSTRAINT platform_account_media_version_check CHECK ((version >= 0))
);


--
-- Name: platform_account_phone; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_account_phone (
    user_id uuid NOT NULL,
    phone character varying(16),
    verified_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    pending_phone character varying(16),
    token_hash character varying(64),
    expires_at timestamp with time zone,
    requested_at timestamp with time zone,
    request_day date,
    request_count integer DEFAULT 0 NOT NULL,
    failures integer DEFAULT 0 NOT NULL,
    CONSTRAINT platform_account_phone_check CHECK (((phone IS NULL) = (verified_at IS NULL))),
    CONSTRAINT platform_account_phone_check1 CHECK (((pending_phone IS NULL) = (token_hash IS NULL))),
    CONSTRAINT platform_account_phone_check2 CHECK (((pending_phone IS NULL) = (expires_at IS NULL))),
    CONSTRAINT platform_account_phone_failures_check CHECK (((failures >= 0) AND (failures <= 5))),
    CONSTRAINT platform_account_phone_request_count_check CHECK (((request_count >= 0) AND (request_count <= 10))),
    CONSTRAINT platform_account_phone_token_hash_check CHECK (((token_hash IS NULL) OR ((token_hash)::text ~ '^[a-f0-9]{64}$'::text))),
    CONSTRAINT platform_account_phone_version_check CHECK ((version >= 0))
);


--
-- Name: platform_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_audit (
    id uuid NOT NULL,
    actor character varying(100) NOT NULL,
    action character varying(100) NOT NULL,
    target_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_address character varying(45),
    user_agent character varying(256)
);


--
-- Name: platform_authority; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_authority (
    user_id uuid NOT NULL,
    authority character varying(120) NOT NULL
);


--
-- Name: platform_dictionary; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_dictionary (
    id uuid NOT NULL,
    code character varying(100) NOT NULL,
    name character varying(200) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    structure character varying(8) DEFAULT 'FLAT'::character varying NOT NULL,
    remark character varying(1000) DEFAULT ''::character varying NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT platform_dictionary_structure_check CHECK (((structure)::text = ANY ((ARRAY['FLAT'::character varying, 'TREE'::character varying])::text[])))
);


--
-- Name: platform_dictionary_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_dictionary_item (
    id uuid NOT NULL,
    dictionary_id uuid NOT NULL,
    parent_id uuid,
    value character varying(200) NOT NULL,
    label character varying(200) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    remark character varying(1000) DEFAULT ''::character varying NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT platform_dictionary_item_check CHECK ((id IS DISTINCT FROM parent_id))
);


--
-- Name: platform_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_role (
    id uuid NOT NULL,
    code character varying(100) NOT NULL,
    name character varying(200) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    data_scope character varying(20) DEFAULT 'ALL'::character varying NOT NULL,
    CONSTRAINT platform_role_data_scope_check CHECK (((data_scope)::text = ANY ((ARRAY['SELF'::character varying, 'ORGANIZATION'::character varying, 'SUBTREE'::character varying, 'ALL'::character varying])::text[])))
);


--
-- Name: platform_role_authority; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_role_authority (
    role_id uuid NOT NULL,
    authority character varying(100) NOT NULL
);


--
-- Name: platform_user_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_user_role (
    user_id uuid NOT NULL,
    role_id uuid NOT NULL
);


--
-- Name: platform_effective_authority; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.platform_effective_authority AS
 SELECT platform_authority.user_id,
    platform_authority.authority
   FROM public.platform_authority
UNION
 SELECT ur.user_id,
    ra.authority
   FROM ((public.platform_user_role ur
     JOIN public.platform_role r ON (((r.id = ur.role_id) AND r.enabled)))
     JOIN public.platform_role_authority ra ON ((ra.role_id = r.id)));


--
-- Name: platform_event_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_event_outbox (
    id uuid NOT NULL,
    source_type character varying(64) NOT NULL,
    event_type character varying(128) NOT NULL,
    event_key character varying(191) NOT NULL,
    payload jsonb NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    available_at timestamp with time zone DEFAULT now() NOT NULL,
    lease_token uuid,
    lease_until timestamp with time zone,
    last_error character varying(1000),
    delivered_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_event_outbox_attempts_check CHECK (((attempts >= 0) AND (attempts <= 8))),
    CONSTRAINT platform_event_outbox_check CHECK ((((status)::text = 'PROCESSING'::text) = ((lease_token IS NOT NULL) AND (lease_until IS NOT NULL)))),
    CONSTRAINT platform_event_outbox_check1 CHECK (((lease_token IS NULL) = (lease_until IS NULL))),
    CONSTRAINT platform_event_outbox_check2 CHECK ((((status)::text = 'DELIVERED'::text) = (delivered_at IS NOT NULL))),
    CONSTRAINT platform_event_outbox_event_key_check CHECK (((event_key)::text <> ''::text)),
    CONSTRAINT platform_event_outbox_event_type_check CHECK (((event_type)::text ~ '^[a-z][a-z0-9._:-]{0,127}$'::text)),
    CONSTRAINT platform_event_outbox_payload_check CHECK (((octet_length((payload)::text) >= 2) AND (octet_length((payload)::text) <= 262144))),
    CONSTRAINT platform_event_outbox_source_type_check CHECK (((source_type)::text ~ '^[a-z][a-z0-9._:-]{0,63}$'::text)),
    CONSTRAINT platform_event_outbox_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'DELIVERED'::character varying, 'FAILED'::character varying, 'DEAD_LETTER'::character varying])::text[])))
);


--
-- Name: platform_event_replay_approval; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_event_replay_approval (
    id uuid NOT NULL,
    event_id uuid NOT NULL,
    approved_by character varying(100) NOT NULL,
    reason character varying(1000) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_event_replay_approval_actor_check CHECK ((length(TRIM(BOTH FROM approved_by)) > 0)),
    CONSTRAINT platform_event_replay_approval_reason_check CHECK ((length(TRIM(BOTH FROM reason)) > 0))
);


--
-- Name: platform_feedback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_feedback (
    id uuid NOT NULL,
    owner_id uuid,
    title character varying(160) NOT NULL,
    detail_html text NOT NULL,
    priority character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    admin_reply character varying(4000),
    handled_by uuid,
    handled_at timestamp with time zone,
    version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT platform_feedback_detail_html_check CHECK ((octet_length(detail_html) <= 800000)),
    CONSTRAINT platform_feedback_title_check CHECK ((length(TRIM(BOTH FROM title)) > 0)),
    CONSTRAINT platform_feedback_version_check CHECK ((version > 0))
);


--
-- Name: platform_feedback_file; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_feedback_file (
    feedback_id uuid NOT NULL,
    file_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: platform_file; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_file (
    id uuid NOT NULL,
    owner_id uuid,
    storage_backend character varying(32) NOT NULL,
    original_name character varying(255) NOT NULL,
    state character varying(16) DEFAULT 'UPLOADING'::character varying NOT NULL,
    size_bytes bigint,
    sha256 character varying(64),
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    reserved_bytes bigint DEFAULT 20971520 NOT NULL,
    upload_expires_at timestamp with time zone DEFAULT (now() + '00:15:00'::interval) NOT NULL,
    orphaned_at timestamp with time zone,
    media_type character varying(100) DEFAULT 'application/octet-stream'::character varying NOT NULL,
    CONSTRAINT platform_file_check CHECK (((size_bytes IS NULL) = (sha256 IS NULL))),
    CONSTRAINT platform_file_check1 CHECK ((((state)::text <> 'READY'::text) OR ((size_bytes IS NOT NULL) AND (sha256 IS NOT NULL)))),
    CONSTRAINT platform_file_media_type_check CHECK (((media_type)::text = ANY ((ARRAY['application/octet-stream'::character varying, 'image/png'::character varying])::text[]))),
    CONSTRAINT platform_file_original_name_check CHECK ((length((original_name)::text) > 0)),
    CONSTRAINT platform_file_orphan_owner_consistency CHECK (((owner_id IS NULL) = (orphaned_at IS NOT NULL))),
    CONSTRAINT platform_file_reserved_bytes_check CHECK ((reserved_bytes >= 0)),
    CONSTRAINT platform_file_sha256_check CHECK (((sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT platform_file_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT platform_file_size_within_reservation CHECK (((size_bytes IS NULL) OR (size_bytes <= reserved_bytes))),
    CONSTRAINT platform_file_state_check CHECK (((state)::text = ANY ((ARRAY['UPLOADING'::character varying, 'READY'::character varying, 'DELETING'::character varying])::text[]))),
    CONSTRAINT platform_file_version_check CHECK ((version >= 0))
);


--
-- Name: platform_global_authority; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.platform_global_authority AS
 SELECT platform_authority.user_id,
    platform_authority.authority
   FROM public.platform_authority
UNION
 SELECT ur.user_id,
    ra.authority
   FROM ((public.platform_user_role ur
     JOIN public.platform_role r ON (((r.id = ur.role_id) AND r.enabled AND ((r.data_scope)::text = 'ALL'::text))))
     JOIN public.platform_role_authority ra ON ((ra.role_id = r.id)));


--
-- Name: platform_localization_entry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_localization_entry (
    id uuid NOT NULL,
    message_key character varying(200) NOT NULL,
    namespace character varying(80) DEFAULT 'platform'::character varying NOT NULL,
    default_message character varying(2000) NOT NULL,
    translations jsonb DEFAULT '{}'::jsonb NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_localization_entry_created_by_check CHECK ((length(TRIM(BOTH FROM created_by)) > 0)),
    CONSTRAINT platform_localization_entry_default_message_check CHECK ((length(TRIM(BOTH FROM default_message)) > 0)),
    CONSTRAINT platform_localization_entry_message_key_check CHECK (((message_key)::text ~ '^[A-Za-z][A-Za-z0-9_.:-]{0,199}$'::text)),
    CONSTRAINT platform_localization_entry_namespace_check CHECK (((namespace)::text ~ '^[a-z][a-z0-9_.-]{0,79}$'::text)),
    CONSTRAINT platform_localization_entry_translations_check CHECK ((jsonb_typeof(translations) = 'object'::text)),
    CONSTRAINT platform_localization_entry_updated_by_check CHECK ((length(TRIM(BOTH FROM updated_by)) > 0)),
    CONSTRAINT platform_localization_entry_version_check CHECK ((version > 0))
);


--
-- Name: platform_localization_release; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_localization_release (
    locale_code character varying(20) NOT NULL,
    release_version bigint DEFAULT 0 NOT NULL,
    published_by character varying(100) NOT NULL,
    published_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_localization_release_locale_code_check CHECK (((locale_code)::text ~ '^[a-z]{2,3}(?:-[A-Z][a-z]{2,4})?$'::text)),
    CONSTRAINT platform_localization_release_published_by_check CHECK ((length(TRIM(BOTH FROM published_by)) > 0)),
    CONSTRAINT platform_localization_release_release_version_check CHECK ((release_version >= 0))
);


--
-- Name: platform_mail_delivery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_mail_delivery (
    id uuid NOT NULL,
    payload character varying(90000) NOT NULL,
    state character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    available_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    lease_token uuid,
    lease_until timestamp with time zone,
    last_result character varying(40),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_mail_delivery_attempts_check CHECK (((attempts >= 0) AND (attempts <= 5))),
    CONSTRAINT platform_mail_delivery_check CHECK ((((state)::text = ANY ((ARRAY['LEASED'::character varying, 'SENDING'::character varying])::text[])) = ((lease_token IS NOT NULL) AND (lease_until IS NOT NULL)))),
    CONSTRAINT platform_mail_delivery_check1 CHECK (((lease_token IS NULL) = (lease_until IS NULL))),
    CONSTRAINT platform_mail_delivery_last_result_check CHECK (((last_result)::text = ANY ((ARRAY['ACCEPTED'::character varying, 'AUTHENTICATION_FAILED'::character varying, 'CONNECTION_FAILED'::character varying, 'DELIVERY_UNCERTAIN'::character varying, 'CONFIGURATION_UNAVAILABLE'::character varying, 'LEASE_EXPIRED'::character varying])::text[]))),
    CONSTRAINT platform_mail_delivery_payload_check CHECK (((payload)::text ~ '^v1:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$'::text)),
    CONSTRAINT platform_mail_delivery_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'LEASED'::character varying, 'SENDING'::character varying, 'ACCEPTED'::character varying, 'FAILED'::character varying, 'UNCERTAIN'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: platform_module_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_module_revision (
    module_state_id uuid NOT NULL,
    version bigint NOT NULL,
    enabled boolean NOT NULL,
    changed_by character varying(100) NOT NULL,
    change_reason character varying(1000) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_module_revision_change_reason_check CHECK ((length(TRIM(BOTH FROM change_reason)) > 0)),
    CONSTRAINT platform_module_revision_changed_by_check CHECK ((length(TRIM(BOTH FROM changed_by)) > 0)),
    CONSTRAINT platform_module_revision_version_check CHECK ((version > 0))
);


--
-- Name: platform_module_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_module_state (
    id uuid NOT NULL,
    module_id character varying(100) NOT NULL,
    enabled boolean NOT NULL,
    version bigint NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_module_state_module_id_check CHECK (((module_id)::text ~ '^[a-z][a-z0-9-]*$'::text)),
    CONSTRAINT platform_module_state_version_check CHECK ((version > 0))
);


--
-- Name: platform_module_package; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_module_package (
    id uuid NOT NULL,
    module_id character varying(100) NOT NULL,
    package_version character varying(64) NOT NULL,
    schema_version character varying(64) NOT NULL,
    api_version character varying(64) NOT NULL,
    artifact_digest character varying(64) NOT NULL,
    manifest_digest character varying(64) NOT NULL,
    artifact_ref character varying(2048) NOT NULL,
    manifest jsonb NOT NULL,
    status character varying(16) DEFAULT 'STAGED'::character varying NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    installed_by character varying(100) NOT NULL,
    install_reason character varying(1000) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    activated_at timestamp with time zone,
    superseded_at timestamp with time zone,
    CONSTRAINT platform_module_package_api_version_check CHECK (((api_version)::text ~ '^[A-Za-z][A-Za-z0-9._/-]{0,63}$'::text)),
    CONSTRAINT platform_module_package_artifact_digest_check CHECK (((artifact_digest)::text ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_module_package_artifact_ref_check CHECK (((artifact_ref)::text ~ '^(https|oci|s3)://[^[:space:]]+$'::text) AND ((artifact_ref)::text !~ '[.][.]'::text)),
    CONSTRAINT platform_module_package_install_reason_check CHECK ((length(TRIM(BOTH FROM install_reason)) > 0)),
    CONSTRAINT platform_module_package_installed_by_check CHECK ((length(TRIM(BOTH FROM installed_by)) > 0)),
    CONSTRAINT platform_module_package_manifest_check CHECK ((jsonb_typeof(manifest) = 'object'::text)),
    CONSTRAINT platform_module_package_manifest_digest_check CHECK (((manifest_digest)::text ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT platform_module_package_module_id_check CHECK (((module_id)::text ~ '^[a-z][a-z0-9-]*$'::text)),
    CONSTRAINT platform_module_package_package_version_check CHECK (((package_version)::text ~ '^[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.-]+)?$'::text)),
    CONSTRAINT platform_module_package_revision_check CHECK ((revision > 0)),
    CONSTRAINT platform_module_package_schema_version_check CHECK (((schema_version)::text ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'::text)),
    CONSTRAINT platform_module_package_status_check CHECK (((status)::text = ANY ((ARRAY['STAGED'::character varying, 'ACTIVE'::character varying, 'SUPERSEDED'::character varying, 'REJECTED'::character varying])::text[])))
);


--
-- Name: platform_navigation_menu; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_navigation_menu (
    id uuid NOT NULL,
    parent_id uuid,
    menu_code character varying(128) NOT NULL,
    label_key character varying(128) NOT NULL,
    label_default character varying(200) NOT NULL,
    menu_type character varying(16) NOT NULL,
    section_key character varying(64),
    path character varying(512),
    icon character varying(80),
    permission_key character varying(120),
    global_only boolean DEFAULT false NOT NULL,
    module_id character varying(100),
    sort_order integer DEFAULT 0 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    built_in boolean DEFAULT false NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    component_key character varying(128),
    CONSTRAINT platform_navigation_component_shape_ck CHECK ((((menu_type)::text <> 'CATALOG'::text) OR (component_key IS NULL))),
    CONSTRAINT platform_navigation_menu_component_key_check CHECK (((component_key IS NULL) OR ((component_key)::text ~ '^[a-z][a-z0-9._:-]{1,127}$'::text))),
    CONSTRAINT platform_navigation_menu_label_default_check CHECK ((length(TRIM(BOTH FROM label_default)) > 0)),
    CONSTRAINT platform_navigation_menu_label_key_check CHECK (((label_key)::text ~ '^[a-z][a-zA-Z0-9._:-]{0,127}$'::text)),
    CONSTRAINT platform_navigation_menu_menu_code_check CHECK (((menu_code)::text ~ '^[a-z][a-z0-9._:-]{1,127}$'::text)),
    CONSTRAINT platform_navigation_menu_menu_type_check CHECK (((menu_type)::text = ANY ((ARRAY['CATALOG'::character varying, 'PAGE'::character varying, 'ACTION'::character varying])::text[]))),
    CONSTRAINT platform_navigation_menu_module_id_check CHECK (((module_id IS NULL) OR ((module_id)::text ~ '^[a-z][a-z0-9-]{0,99}$'::text))),
    CONSTRAINT platform_navigation_menu_path_check CHECK (((path IS NULL) OR (((path)::text ~~ '/dashboard/%'::text) AND ((path)::text !~~ '%?%'::text) AND ((path)::text !~~ '%#%'::text)))),
    CONSTRAINT platform_navigation_menu_permission_key_check CHECK (((permission_key IS NULL) OR ((permission_key)::text ~ '^[a-z][a-z0-9:._-]{0,119}$'::text))),
    CONSTRAINT platform_navigation_menu_section_key_check CHECK (((section_key IS NULL) OR ((section_key)::text ~ '^[a-z][a-z0-9-]{0,63}$'::text))),
    CONSTRAINT platform_navigation_menu_sort_order_check CHECK (((sort_order >= 0) AND (sort_order <= 1000000))),
    CONSTRAINT platform_navigation_menu_version_check CHECK ((version > 0)),
    CONSTRAINT platform_navigation_parent_self_ck CHECK ((parent_id IS DISTINCT FROM id)),
    CONSTRAINT platform_navigation_shape_ck CHECK (((((menu_type)::text = 'CATALOG'::text) AND (path IS NULL) AND (section_key IS NULL)) OR (((menu_type)::text = ANY ((ARRAY['PAGE'::character varying, 'ACTION'::character varying])::text[])) AND ((path IS NOT NULL) OR (section_key IS NOT NULL)))))
);


--
-- Name: platform_notification; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_notification (
    id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    event_id uuid,
    dedupe_key character varying(191) NOT NULL,
    kind character varying(80) NOT NULL,
    title_key character varying(128) NOT NULL,
    body_key character varying(128) NOT NULL,
    params jsonb NOT NULL,
    href character varying(512),
    read_at timestamp with time zone,
    expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_notification_body_key_check CHECK (((body_key)::text ~ '^[A-Za-z][A-Za-z0-9._:-]{0,127}$'::text)),
    CONSTRAINT platform_notification_dedupe_key_check CHECK (((dedupe_key)::text ~ '^[A-Za-z][A-Za-z0-9._:-]{0,190}$'::text)),
    CONSTRAINT platform_notification_href_check CHECK (((href IS NULL) OR (((href)::text ~~ '/%'::text) AND ((href)::text !~~ '//%'::text)))),
    CONSTRAINT platform_notification_kind_check CHECK (((kind)::text ~ '^[A-Za-z][A-Za-z0-9._:-]{0,79}$'::text)),
    CONSTRAINT platform_notification_params_check CHECK (((octet_length((params)::text) >= 2) AND (octet_length((params)::text) <= 8192))),
    CONSTRAINT platform_notification_title_key_check CHECK (((title_key)::text ~ '^[A-Za-z][A-Za-z0-9._:-]{0,127}$'::text))
);


--
-- Name: platform_organization; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_organization (
    id uuid NOT NULL,
    parent_id uuid,
    code character varying(100) NOT NULL,
    name character varying(200) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT platform_organization_check CHECK ((parent_id IS DISTINCT FROM id))
);


--
-- Name: platform_password_reset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_password_reset (
    user_id uuid NOT NULL,
    token_hash character varying(64),
    password_snapshot text,
    email_version bigint,
    expires_at timestamp with time zone,
    requested_at timestamp with time zone NOT NULL,
    request_day date NOT NULL,
    request_count integer NOT NULL,
    failures integer DEFAULT 0 NOT NULL,
    CONSTRAINT platform_password_reset_check CHECK (((token_hash IS NULL) = (password_snapshot IS NULL))),
    CONSTRAINT platform_password_reset_check1 CHECK (((token_hash IS NULL) = (email_version IS NULL))),
    CONSTRAINT platform_password_reset_check2 CHECK (((token_hash IS NULL) = (expires_at IS NULL))),
    CONSTRAINT platform_password_reset_failures_check CHECK (((failures >= 0) AND (failures <= 5))),
    CONSTRAINT platform_password_reset_request_count_check CHECK (((request_count >= 1) AND (request_count <= 10))),
    CONSTRAINT platform_password_reset_token_hash_check CHECK (((token_hash IS NULL) OR ((token_hash)::text ~ '^[a-f0-9]{64}$'::text)))
);


--
-- Name: platform_passkey_challenge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_passkey_challenge (
    id uuid NOT NULL,
    kind character varying(16) NOT NULL,
    challenge bytea NOT NULL,
    user_id uuid,
    user_handle bytea,
    session_id character varying(200) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_passkey_challenge_kind_check CHECK (((kind)::text = ANY ((ARRAY['REGISTRATION'::character varying, 'AUTHENTICATION'::character varying])::text[]))),
    CONSTRAINT platform_passkey_challenge_bytes_check CHECK (((octet_length(challenge) >= 16) AND (octet_length(challenge) <= 128))),
    CONSTRAINT platform_passkey_challenge_user_consistency_check CHECK (((kind)::text = 'REGISTRATION'::text) = ((user_id IS NOT NULL) AND (user_handle IS NOT NULL)))
);


--
-- Name: platform_passkey_credential; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_passkey_credential (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    user_handle bytea NOT NULL,
    credential_id bytea NOT NULL,
    public_key_cose bytea NOT NULL,
    sign_count bigint DEFAULT 0 NOT NULL,
    transports character varying(255) DEFAULT ''::character varying NOT NULL,
    backup_eligible boolean DEFAULT false NOT NULL,
    backup_state boolean DEFAULT false NOT NULL,
    label character varying(128) DEFAULT '通行密钥'::character varying NOT NULL,
    last_used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_passkey_credential_sign_count_check CHECK ((sign_count >= 0)),
    CONSTRAINT platform_passkey_credential_bytes_check CHECK (((octet_length(user_handle) >= 16) AND (octet_length(user_handle) <= 128) AND (octet_length(credential_id) >= 16) AND (octet_length(credential_id) <= 1024) AND (octet_length(public_key_cose) >= 16) AND (octet_length(public_key_cose) <= 4096))),
    CONSTRAINT platform_passkey_credential_label_check CHECK ((length(TRIM(BOTH FROM label)) > 0))
);


--
-- Name: platform_profile_dictionary_selection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_profile_dictionary_selection (
    user_id uuid NOT NULL,
    field_key character varying(64) NOT NULL,
    item_id uuid NOT NULL
);


--
-- Name: platform_rate_bucket; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_rate_bucket (
    bucket_key character varying(80) CONSTRAINT platform_login_bucket_bucket_key_not_null NOT NULL,
    window_start timestamp with time zone CONSTRAINT platform_login_bucket_window_start_not_null NOT NULL,
    attempts integer CONSTRAINT platform_login_bucket_attempts_not_null NOT NULL,
    CONSTRAINT platform_login_bucket_attempts_check CHECK ((attempts >= 0))
);


--
-- Name: platform_recovery_capability; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.platform_recovery_capability AS
 SELECT user_id,
    authority AS capability
   FROM public.platform_global_authority g
  WHERE (((authority)::text = ANY ((ARRAY['platform:users:write'::character varying, 'platform:roles:write'::character varying])::text[])) OR (((authority)::text = 'platform:settings:write'::text) AND (EXISTS ( SELECT 1
           FROM public.platform_effective_authority a
          WHERE ((a.user_id = g.user_id) AND ((a.authority)::text = 'platform:settings:read'::text))))));


--
-- Name: platform_secret; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_secret (
    secret_context character varying(512) NOT NULL,
    ciphertext character varying(90000),
    version bigint NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_secret_ciphertext_check CHECK (((ciphertext IS NULL) OR ((ciphertext)::text ~ '^v1:[A-Za-z0-9_-]{1,32}:[A-Za-z0-9_-]{38,}$'::text))),
    CONSTRAINT platform_secret_secret_context_check CHECK (((secret_context)::text ~ '^[A-Za-z0-9_:/.-]+$'::text)),
    CONSTRAINT platform_secret_version_check CHECK ((version > 0))
);


--
-- Name: platform_sensitive_word; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_sensitive_word (
    id uuid NOT NULL,
    word character varying(128) NOT NULL,
    normalized_word character varying(128) NOT NULL,
    category character varying(64) DEFAULT 'DEFAULT'::character varying NOT NULL,
    severity character varying(16) DEFAULT 'MEDIUM'::character varying NOT NULL,
    action character varying(16) DEFAULT 'BLOCK'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_sensitive_word_action_check CHECK (((action)::text = ANY ((ARRAY['BLOCK'::character varying, 'LOG_ONLY'::character varying])::text[]))),
    CONSTRAINT platform_sensitive_word_category_check CHECK ((length(TRIM(BOTH FROM category)) > 0)),
    CONSTRAINT platform_sensitive_word_created_by_check CHECK ((length(TRIM(BOTH FROM created_by)) > 0)),
    CONSTRAINT platform_sensitive_word_normalized_word_check CHECK ((length(TRIM(BOTH FROM normalized_word)) > 0)),
    CONSTRAINT platform_sensitive_word_severity_check CHECK (((severity)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'CRITICAL'::character varying])::text[]))),
    CONSTRAINT platform_sensitive_word_updated_by_check CHECK ((length(TRIM(BOTH FROM updated_by)) > 0)),
    CONSTRAINT platform_sensitive_word_version_check CHECK ((version > 0)),
    CONSTRAINT platform_sensitive_word_word_check CHECK ((length(TRIM(BOTH FROM word)) > 0))
);


--
-- Name: platform_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_setting (
    id uuid NOT NULL,
    section character varying(100) NOT NULL,
    version bigint NOT NULL,
    payload jsonb NOT NULL,
    updated_by character varying(100) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_setting_version_check CHECK ((version > 0))
);


--
-- Name: platform_setting_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_setting_revision (
    setting_id uuid NOT NULL,
    version bigint NOT NULL,
    payload jsonb NOT NULL,
    changed_by character varying(100) NOT NULL,
    change_reason character varying(1000) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    source_version bigint
);


--
-- Name: platform_sms_delivery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_sms_delivery (
    id uuid NOT NULL,
    payload character varying(90000) NOT NULL,
    state character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    available_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    lease_token uuid,
    lease_until timestamp with time zone,
    last_result character varying(40),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_sms_delivery_attempts_check CHECK (((attempts >= 0) AND (attempts <= 5))),
    CONSTRAINT platform_sms_delivery_check CHECK ((((state)::text = ANY ((ARRAY['LEASED'::character varying, 'SENDING'::character varying])::text[])) = ((lease_token IS NOT NULL) AND (lease_until IS NOT NULL)))),
    CONSTRAINT platform_sms_delivery_check1 CHECK (((lease_token IS NULL) = (lease_until IS NULL))),
    CONSTRAINT platform_sms_delivery_last_result_check CHECK (((last_result)::text = ANY ((ARRAY['ACCEPTED'::character varying, 'AUTHENTICATION_FAILED'::character varying, 'CONNECTION_FAILED'::character varying, 'DELIVERY_UNCERTAIN'::character varying, 'CONFIGURATION_UNAVAILABLE'::character varying, 'LEASE_EXPIRED'::character varying])::text[]))),
    CONSTRAINT platform_sms_delivery_payload_check CHECK (((payload)::text ~ '^v1:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$'::text)),
    CONSTRAINT platform_sms_delivery_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'LEASED'::character varying, 'SENDING'::character varying, 'ACCEPTED'::character varying, 'FAILED'::character varying, 'UNCERTAIN'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: platform_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_user (
    id uuid NOT NULL,
    username character varying(100) NOT NULL,
    display_name character varying(200) NOT NULL,
    password_hash character varying(100) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    real_name character varying(200) DEFAULT ''::character varying NOT NULL,
    birth_month character varying(7) DEFAULT ''::character varying NOT NULL,
    region character varying(200) DEFAULT ''::character varying NOT NULL,
    profile_values jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT platform_user_profile_values_check CHECK ((jsonb_typeof(profile_values) = 'object'::text))
);


--
-- Name: platform_user_organization; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_user_organization (
    user_id uuid NOT NULL,
    organization_id uuid NOT NULL
);


--
-- Name: spring_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.spring_session (
    primary_id character(36) NOT NULL,
    session_id character(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name character varying(100)
);


--
-- Name: spring_session_attributes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.spring_session_attributes (
    session_primary_id character(36) NOT NULL,
    attribute_name character varying(200) NOT NULL,
    attribute_bytes bytea NOT NULL
);


--
-- Name: platform_account_draft platform_account_draft_owner_id_draft_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_draft
    ADD CONSTRAINT platform_account_draft_owner_id_draft_key_key UNIQUE (owner_id, draft_key);


--
-- Name: platform_account_draft platform_account_draft_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_draft
    ADD CONSTRAINT platform_account_draft_pkey PRIMARY KEY (id);


--
-- Name: platform_account_email platform_account_email_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_email
    ADD CONSTRAINT platform_account_email_pkey PRIMARY KEY (user_id);


--
-- Name: platform_account_media platform_account_media_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_media
    ADD CONSTRAINT platform_account_media_pkey PRIMARY KEY (user_id);


--
-- Name: platform_account_phone platform_account_phone_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_phone
    ADD CONSTRAINT platform_account_phone_pkey PRIMARY KEY (user_id);


--
-- Name: platform_audit platform_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_audit
    ADD CONSTRAINT platform_audit_pkey PRIMARY KEY (id);


--
-- Name: platform_authority platform_authority_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_authority
    ADD CONSTRAINT platform_authority_pkey PRIMARY KEY (user_id, authority);


--
-- Name: platform_dictionary platform_dictionary_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary
    ADD CONSTRAINT platform_dictionary_code_key UNIQUE (code);


--
-- Name: platform_dictionary_item platform_dictionary_item_dictionary_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary_item
    ADD CONSTRAINT platform_dictionary_item_dictionary_id_id_key UNIQUE (dictionary_id, id);


--
-- Name: platform_dictionary_item platform_dictionary_item_dictionary_id_value_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary_item
    ADD CONSTRAINT platform_dictionary_item_dictionary_id_value_key UNIQUE (dictionary_id, value);


--
-- Name: platform_dictionary_item platform_dictionary_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary_item
    ADD CONSTRAINT platform_dictionary_item_pkey PRIMARY KEY (id);


--
-- Name: platform_dictionary platform_dictionary_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary
    ADD CONSTRAINT platform_dictionary_pkey PRIMARY KEY (id);


--
-- Name: platform_event_outbox platform_event_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_event_outbox
    ADD CONSTRAINT platform_event_outbox_pkey PRIMARY KEY (id);


--
-- Name: platform_event_outbox platform_event_outbox_source_type_event_type_event_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_event_outbox
    ADD CONSTRAINT platform_event_outbox_source_type_event_type_event_key_key UNIQUE (source_type, event_type, event_key);


--
-- Name: platform_event_replay_approval platform_event_replay_approval_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_event_replay_approval
    ADD CONSTRAINT platform_event_replay_approval_pkey PRIMARY KEY (id);


--
-- Name: platform_feedback_file platform_feedback_file_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_feedback_file
    ADD CONSTRAINT platform_feedback_file_pkey PRIMARY KEY (feedback_id, file_id);


--
-- Name: platform_feedback platform_feedback_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_feedback
    ADD CONSTRAINT platform_feedback_pkey PRIMARY KEY (id);


--
-- Name: platform_file platform_file_avatar_target; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_file
    ADD CONSTRAINT platform_file_avatar_target UNIQUE (id, owner_id, state, media_type);


--
-- Name: platform_file platform_file_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_file
    ADD CONSTRAINT platform_file_pkey PRIMARY KEY (id);


--
-- Name: platform_localization_entry platform_localization_entry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_localization_entry
    ADD CONSTRAINT platform_localization_entry_pkey PRIMARY KEY (id);


--
-- Name: platform_localization_release platform_localization_release_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_localization_release
    ADD CONSTRAINT platform_localization_release_pkey PRIMARY KEY (locale_code);


--
-- Name: platform_rate_bucket platform_login_bucket_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_rate_bucket
    ADD CONSTRAINT platform_login_bucket_pkey PRIMARY KEY (bucket_key);


--
-- Name: platform_mail_delivery platform_mail_delivery_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_mail_delivery
    ADD CONSTRAINT platform_mail_delivery_pkey PRIMARY KEY (id);


--
-- Name: platform_module_revision platform_module_revision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_module_revision
    ADD CONSTRAINT platform_module_revision_pkey PRIMARY KEY (module_state_id, version);


--
-- Name: platform_module_state platform_module_state_module_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_module_state
    ADD CONSTRAINT platform_module_state_module_id_key UNIQUE (module_id);


--
-- Name: platform_module_state platform_module_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_module_state
    ADD CONSTRAINT platform_module_state_pkey PRIMARY KEY (id);


--
-- Name: platform_module_package platform_module_package_identity_uq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_module_package
    ADD CONSTRAINT platform_module_package_identity_uq UNIQUE (module_id, package_version, artifact_digest);


--
-- Name: platform_module_package platform_module_package_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_module_package
    ADD CONSTRAINT platform_module_package_pkey PRIMARY KEY (id);


--
-- Name: platform_navigation_menu platform_navigation_menu_menu_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_navigation_menu
    ADD CONSTRAINT platform_navigation_menu_menu_code_key UNIQUE (menu_code);


--
-- Name: platform_navigation_menu platform_navigation_menu_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_navigation_menu
    ADD CONSTRAINT platform_navigation_menu_pkey PRIMARY KEY (id);


--
-- Name: platform_notification platform_notification_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_notification
    ADD CONSTRAINT platform_notification_pkey PRIMARY KEY (id);


--
-- Name: platform_organization platform_organization_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_organization
    ADD CONSTRAINT platform_organization_code_key UNIQUE (code);


--
-- Name: platform_organization platform_organization_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_organization
    ADD CONSTRAINT platform_organization_pkey PRIMARY KEY (id);


--
-- Name: platform_password_reset platform_password_reset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_password_reset
    ADD CONSTRAINT platform_password_reset_pkey PRIMARY KEY (user_id);


--
-- Name: platform_passkey_challenge platform_passkey_challenge_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_passkey_challenge
    ADD CONSTRAINT platform_passkey_challenge_pkey PRIMARY KEY (id);


--
-- Name: platform_passkey_credential platform_passkey_credential_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_passkey_credential
    ADD CONSTRAINT platform_passkey_credential_pkey PRIMARY KEY (id);


--
-- Name: platform_passkey_credential platform_passkey_credential_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_passkey_credential
    ADD CONSTRAINT platform_passkey_credential_id_key UNIQUE (credential_id);


--
-- Name: platform_profile_dictionary_selection platform_profile_dictionary_selection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_profile_dictionary_selection
    ADD CONSTRAINT platform_profile_dictionary_selection_pkey PRIMARY KEY (user_id, field_key);


--
-- Name: platform_role_authority platform_role_authority_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_authority
    ADD CONSTRAINT platform_role_authority_pkey PRIMARY KEY (role_id, authority);


--
-- Name: platform_role platform_role_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role
    ADD CONSTRAINT platform_role_code_key UNIQUE (code);


--
-- Name: platform_role platform_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role
    ADD CONSTRAINT platform_role_pkey PRIMARY KEY (id);


--
-- Name: platform_secret platform_secret_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_secret
    ADD CONSTRAINT platform_secret_pkey PRIMARY KEY (secret_context);


--
-- Name: platform_sensitive_word platform_sensitive_word_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_sensitive_word
    ADD CONSTRAINT platform_sensitive_word_pkey PRIMARY KEY (id);


--
-- Name: platform_setting platform_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_setting
    ADD CONSTRAINT platform_setting_pkey PRIMARY KEY (id);


--
-- Name: platform_setting_revision platform_setting_revision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_setting_revision
    ADD CONSTRAINT platform_setting_revision_pkey PRIMARY KEY (setting_id, version);


--
-- Name: platform_setting platform_setting_section_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_setting
    ADD CONSTRAINT platform_setting_section_key UNIQUE (section);


--
-- Name: platform_sms_delivery platform_sms_delivery_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_sms_delivery
    ADD CONSTRAINT platform_sms_delivery_pkey PRIMARY KEY (id);


--
-- Name: platform_user_organization platform_user_organization_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user_organization
    ADD CONSTRAINT platform_user_organization_pkey PRIMARY KEY (user_id, organization_id);


--
-- Name: platform_user platform_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user
    ADD CONSTRAINT platform_user_pkey PRIMARY KEY (id);


--
-- Name: platform_user_role platform_user_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user_role
    ADD CONSTRAINT platform_user_role_pkey PRIMARY KEY (user_id, role_id);


--
-- Name: platform_user platform_user_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user
    ADD CONSTRAINT platform_user_username_key UNIQUE (username);


--
-- Name: spring_session_attributes spring_session_attributes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_pkey PRIMARY KEY (session_primary_id, attribute_name);


--
-- Name: spring_session spring_session_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.spring_session
    ADD CONSTRAINT spring_session_pkey PRIMARY KEY (primary_id);


--
-- Name: platform_account_email_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_account_email_unique ON public.platform_account_email USING btree (lower((email)::text)) WHERE (email IS NOT NULL);


--
-- Name: platform_account_media_avatar; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_account_media_avatar ON public.platform_account_media USING btree (avatar_file_id) WHERE (avatar_file_id IS NOT NULL);


--
-- Name: platform_account_phone_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_account_phone_pending ON public.platform_account_phone USING btree (requested_at) WHERE (pending_phone IS NOT NULL);


--
-- Name: platform_account_phone_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_account_phone_unique ON public.platform_account_phone USING btree (phone) WHERE (phone IS NOT NULL);


--
-- Name: platform_audit_account_sessions; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_audit_account_sessions ON public.platform_audit USING btree (actor, created_at DESC, id DESC) WHERE ((action)::text = 'session.revoke'::text);


--
-- Name: platform_audit_account_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_audit_account_target ON public.platform_audit USING btree (target_id, created_at DESC, id DESC) WHERE ((action)::text = ANY ((ARRAY['account.loginSucceeded'::character varying, 'account.loginFailed'::character varying, 'account.passwordChanged'::character varying, 'account.passwordRecoveryRequested'::character varying, 'account.passwordRecovered'::character varying, 'account.passwordRecoveryFailed'::character varying, 'account.emailVerificationRequested'::character varying, 'account.emailVerified'::character varying, 'account.emailVerificationFailed'::character varying])::text[]));


--
-- Name: platform_audit_actor_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_audit_actor_idx ON public.platform_audit USING btree (actor, created_at DESC, id);


--
-- Name: platform_audit_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_audit_created_idx ON public.platform_audit USING btree (created_at DESC);


--
-- Name: platform_dictionary_item_parent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_dictionary_item_parent_idx ON public.platform_dictionary_item USING btree (dictionary_id, parent_id, sort_order, id);


--
-- Name: platform_event_outbox_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_event_outbox_created ON public.platform_event_outbox USING btree (created_at DESC, id DESC);


--
-- Name: platform_event_outbox_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_event_outbox_due ON public.platform_event_outbox USING btree (status, available_at, created_at, id) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: platform_event_outbox_lease; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_event_outbox_lease ON public.platform_event_outbox USING btree (lease_until, updated_at, id) WHERE ((status)::text = 'PROCESSING'::text);


--
-- Name: platform_event_replay_approval_event; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_event_replay_approval_event ON public.platform_event_replay_approval USING btree (event_id, created_at DESC, id DESC);


--
-- Name: platform_module_package_active_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_module_package_active_uq ON public.platform_module_package USING btree (module_id) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: platform_module_package_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_module_package_lookup ON public.platform_module_package USING btree (module_id, status, created_at DESC, id DESC);


--
-- Name: platform_feedback_file_file; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_feedback_file_file ON public.platform_feedback_file USING btree (file_id);


--
-- Name: platform_feedback_owner_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_feedback_owner_recent ON public.platform_feedback USING btree (owner_id, created_at DESC, id DESC);


--
-- Name: platform_feedback_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_feedback_recent ON public.platform_feedback USING btree (created_at DESC, id DESC);


--
-- Name: platform_file_cleanup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_file_cleanup ON public.platform_file USING btree (state, updated_at, id);


--
-- Name: platform_file_orphan_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_file_orphan_retention ON public.platform_file USING btree (orphaned_at, id) WHERE ((owner_id IS NULL) AND ((state)::text = 'READY'::text));


--
-- Name: platform_file_owner_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_file_owner_created ON public.platform_file USING btree (owner_id, created_at DESC, id);


--
-- Name: platform_file_upload_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_file_upload_expiry ON public.platform_file USING btree (upload_expires_at, id) WHERE ((state)::text = 'UPLOADING'::text);


--
-- Name: platform_localization_entry_key_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_localization_entry_key_uq ON public.platform_localization_entry USING btree (message_key) WHERE (deleted = false);


--
-- Name: platform_localization_entry_list_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_localization_entry_list_idx ON public.platform_localization_entry USING btree (namespace, enabled, deleted, updated_at DESC, id);


--
-- Name: platform_mail_leases; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_mail_leases ON public.platform_mail_delivery USING btree (lease_until) WHERE ((state)::text = ANY ((ARRAY['LEASED'::character varying, 'SENDING'::character varying])::text[]));


--
-- Name: platform_mail_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_mail_pending ON public.platform_mail_delivery USING btree (available_at, created_at, id) WHERE ((state)::text = 'PENDING'::text);


--
-- Name: platform_mail_pending_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_mail_pending_expiry ON public.platform_mail_delivery USING btree (expires_at) WHERE ((state)::text = 'PENDING'::text);


--
-- Name: platform_navigation_page_path_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_navigation_page_path_uq ON public.platform_navigation_menu USING btree (path) WHERE ((deleted = false) AND ((menu_type)::text = 'PAGE'::text) AND (path IS NOT NULL));


--
-- Name: platform_navigation_page_section_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_navigation_page_section_uq ON public.platform_navigation_menu USING btree (section_key) WHERE ((deleted = false) AND ((menu_type)::text = 'PAGE'::text) AND (section_key IS NOT NULL));


--
-- Name: platform_navigation_parent_order_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_navigation_parent_order_idx ON public.platform_navigation_menu USING btree (parent_id, sort_order, id);


--
-- Name: platform_navigation_visible_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_navigation_visible_idx ON public.platform_navigation_menu USING btree (enabled, deleted, sort_order, id);


--
-- Name: platform_notification_dedupe; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_notification_dedupe ON public.platform_notification USING btree (recipient_id, dedupe_key);


--
-- Name: platform_notification_inbox; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_notification_inbox ON public.platform_notification USING btree (recipient_id, created_at DESC, id DESC);


--
-- Name: platform_notification_unread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_notification_unread ON public.platform_notification USING btree (recipient_id, created_at DESC, id DESC) WHERE (read_at IS NULL);


--
-- Name: platform_organization_parent_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_organization_parent_idx ON public.platform_organization USING btree (parent_id);


--
-- Name: platform_passkey_challenge_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_passkey_challenge_expiry ON public.platform_passkey_challenge USING btree (expires_at, id) WHERE (consumed_at IS NULL);


--
-- Name: platform_passkey_credential_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_passkey_credential_user ON public.platform_passkey_credential USING btree (user_id, created_at DESC, id DESC);


--
-- Name: platform_profile_dictionary_selection_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_profile_dictionary_selection_item ON public.platform_profile_dictionary_selection USING btree (item_id);


--
-- Name: platform_rate_bucket_window_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_rate_bucket_window_idx ON public.platform_rate_bucket USING btree (window_start);


--
-- Name: platform_sensitive_word_enabled_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_sensitive_word_enabled_idx ON public.platform_sensitive_word USING btree (enabled, deleted, updated_at DESC, id);


--
-- Name: platform_sensitive_word_normalized_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_sensitive_word_normalized_uq ON public.platform_sensitive_word USING btree (normalized_word) WHERE (deleted = false);


--
-- Name: platform_sms_leases; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_sms_leases ON public.platform_sms_delivery USING btree (lease_until, updated_at, id) WHERE ((state)::text = ANY ((ARRAY['LEASED'::character varying, 'SENDING'::character varying])::text[]));


--
-- Name: platform_sms_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_sms_pending ON public.platform_sms_delivery USING btree (available_at, created_at, id) WHERE ((state)::text = 'PENDING'::text);


--
-- Name: platform_sms_pending_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_sms_pending_expiry ON public.platform_sms_delivery USING btree (expires_at) WHERE ((state)::text = 'PENDING'::text);


--
-- Name: platform_user_organization_org_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_user_organization_org_idx ON public.platform_user_organization USING btree (organization_id);


--
-- Name: platform_user_role_role_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_user_role_role_idx ON public.platform_user_role USING btree (role_id);


--
-- Name: spring_session_ix1; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX spring_session_ix1 ON public.spring_session USING btree (session_id);


--
-- Name: spring_session_ix2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spring_session_ix2 ON public.spring_session USING btree (expiry_time);


--
-- Name: spring_session_ix3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spring_session_ix3 ON public.spring_session USING btree (principal_name);


--
-- Name: platform_file platform_file_track_owner; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER platform_file_track_owner BEFORE INSERT OR UPDATE OF owner_id ON public.platform_file FOR EACH ROW EXECUTE FUNCTION public.platform_file_track_owner();


--
-- Name: platform_account_draft platform_account_draft_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_draft
    ADD CONSTRAINT platform_account_draft_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_account_email platform_account_email_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_email
    ADD CONSTRAINT platform_account_email_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_account_media platform_account_media_avatar_file_id_user_id_file_state_m_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_media
    ADD CONSTRAINT platform_account_media_avatar_file_id_user_id_file_state_m_fkey FOREIGN KEY (avatar_file_id, user_id, file_state, media_type) REFERENCES public.platform_file(id, owner_id, state, media_type);


--
-- Name: platform_account_media platform_account_media_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_media
    ADD CONSTRAINT platform_account_media_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_account_phone platform_account_phone_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_account_phone
    ADD CONSTRAINT platform_account_phone_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_authority platform_authority_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_authority
    ADD CONSTRAINT platform_authority_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_dictionary_item platform_dictionary_item_dictionary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary_item
    ADD CONSTRAINT platform_dictionary_item_dictionary_id_fkey FOREIGN KEY (dictionary_id) REFERENCES public.platform_dictionary(id) ON DELETE RESTRICT;


--
-- Name: platform_dictionary_item platform_dictionary_item_dictionary_id_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_dictionary_item
    ADD CONSTRAINT platform_dictionary_item_dictionary_id_parent_id_fkey FOREIGN KEY (dictionary_id, parent_id) REFERENCES public.platform_dictionary_item(dictionary_id, id) ON DELETE RESTRICT;


--
-- Name: platform_feedback_file platform_feedback_file_feedback_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_feedback_file
    ADD CONSTRAINT platform_feedback_file_feedback_id_fkey FOREIGN KEY (feedback_id) REFERENCES public.platform_feedback(id) ON DELETE CASCADE;


--
-- Name: platform_event_replay_approval platform_event_replay_approval_event_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_event_replay_approval
    ADD CONSTRAINT platform_event_replay_approval_event_id_fkey FOREIGN KEY (event_id) REFERENCES public.platform_event_outbox(id) ON DELETE RESTRICT;


--
-- Name: platform_feedback_file platform_feedback_file_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_feedback_file
    ADD CONSTRAINT platform_feedback_file_file_id_fkey FOREIGN KEY (file_id) REFERENCES public.platform_file(id) ON DELETE RESTRICT;


--
-- Name: platform_feedback platform_feedback_handled_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_feedback
    ADD CONSTRAINT platform_feedback_handled_by_fkey FOREIGN KEY (handled_by) REFERENCES public.platform_user(id) ON DELETE SET NULL;


--
-- Name: platform_feedback platform_feedback_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_feedback
    ADD CONSTRAINT platform_feedback_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.platform_user(id) ON DELETE SET NULL;


--
-- Name: platform_file platform_file_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_file
    ADD CONSTRAINT platform_file_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.platform_user(id) ON DELETE SET NULL;


--
-- Name: platform_module_revision platform_module_revision_module_state_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_module_revision
    ADD CONSTRAINT platform_module_revision_module_state_id_fkey FOREIGN KEY (module_state_id) REFERENCES public.platform_module_state(id) ON DELETE RESTRICT;


--
-- Name: platform_navigation_menu platform_navigation_menu_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_navigation_menu
    ADD CONSTRAINT platform_navigation_menu_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.platform_navigation_menu(id) ON DELETE RESTRICT;


--
-- Name: platform_notification platform_notification_event_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_notification
    ADD CONSTRAINT platform_notification_event_id_fkey FOREIGN KEY (event_id) REFERENCES public.platform_event_outbox(id) ON DELETE SET NULL;


--
-- Name: platform_notification platform_notification_recipient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_notification
    ADD CONSTRAINT platform_notification_recipient_id_fkey FOREIGN KEY (recipient_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_organization platform_organization_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_organization
    ADD CONSTRAINT platform_organization_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.platform_organization(id) ON DELETE RESTRICT;


--
-- Name: platform_password_reset platform_password_reset_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_password_reset
    ADD CONSTRAINT platform_password_reset_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_passkey_challenge platform_passkey_challenge_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_passkey_challenge
    ADD CONSTRAINT platform_passkey_challenge_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_passkey_credential platform_passkey_credential_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_passkey_credential
    ADD CONSTRAINT platform_passkey_credential_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_profile_dictionary_selection platform_profile_dictionary_selection_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_profile_dictionary_selection
    ADD CONSTRAINT platform_profile_dictionary_selection_item_id_fkey FOREIGN KEY (item_id) REFERENCES public.platform_dictionary_item(id) ON DELETE RESTRICT;


--
-- Name: platform_profile_dictionary_selection platform_profile_dictionary_selection_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_profile_dictionary_selection
    ADD CONSTRAINT platform_profile_dictionary_selection_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_role_authority platform_role_authority_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_role_authority
    ADD CONSTRAINT platform_role_authority_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.platform_role(id) ON DELETE CASCADE;


--
-- Name: platform_setting_revision platform_setting_revision_setting_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_setting_revision
    ADD CONSTRAINT platform_setting_revision_setting_id_fkey FOREIGN KEY (setting_id) REFERENCES public.platform_setting(id) ON DELETE RESTRICT;


--
-- Name: platform_user_organization platform_user_organization_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user_organization
    ADD CONSTRAINT platform_user_organization_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.platform_organization(id) ON DELETE RESTRICT;


--
-- Name: platform_user_organization platform_user_organization_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user_organization
    ADD CONSTRAINT platform_user_organization_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_user_role platform_user_role_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user_role
    ADD CONSTRAINT platform_user_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.platform_role(id) ON DELETE RESTRICT;


--
-- Name: platform_user_role platform_user_role_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_user_role
    ADD CONSTRAINT platform_user_role_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.platform_user(id) ON DELETE CASCADE;


--
-- Name: platform_setting_revision setting_revision_source; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_setting_revision
    ADD CONSTRAINT setting_revision_source FOREIGN KEY (setting_id, source_version) REFERENCES public.platform_setting_revision(setting_id, version);


--
-- Name: spring_session_attributes spring_session_attributes_session_primary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_session_primary_id_fkey FOREIGN KEY (session_primary_id) REFERENCES public.spring_session(primary_id) ON DELETE CASCADE;

-- -----------------------------------------------------------------------------
-- Reference data and platform capabilities
-- -----------------------------------------------------------------------------
-- The application creates the first administrator after Flyway completes. These
-- grants are retained for a restored database that already contains admin.
INSERT INTO platform_authority(user_id,authority)
SELECT id,authority FROM platform_user
CROSS JOIN (VALUES
 ('platform:users:write'),('platform:roles:read'),('platform:roles:write'),
 ('platform:organizations:read'),('platform:organizations:write'),
 ('platform:audit:read'),('platform:dictionaries:read'),('platform:dictionaries:write'),
 ('platform:settings:read'),('platform:settings:write'),('platform:modules:write'),
 ('platform:events:write'),
 ('platform:navigation:read'),('platform:navigation:write'),
 ('platform:sensitive-words:read'),('platform:sensitive-words:write'),('platform:sensitive-words:import'),
 ('platform:localization:read'),('platform:localization:write'),('platform:localization:import'),
 ('platform:feedback:read'),('platform:feedback:create'),('platform:feedback:manage'),
 ('platform:modules:read'),('platform:users:read'),
 ('platform:files:read'),('platform:files:write'),('platform:notifications:read'),('platform:notifications:write')
) grants(authority)
WHERE username='admin' ON CONFLICT DO NOTHING;

-- Stable module identifiers keep revisions and audit references readable after
-- dump/restore. Module rows are independent from executable code registration.
INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES
 ('00000000-0000-0000-0000-000000000101','audit-console',true,1),
 ('00000000-0000-0000-0000-000000000102','feedback',true,1),
 ('00000000-0000-0000-0000-000000000103','sensitive-words',true,1),
 ('00000000-0000-0000-0000-000000000104','localization',true,1)
ON CONFLICT(module_id) DO NOTHING;

INSERT INTO platform_module_revision(module_state_id,version,enabled,changed_by,change_reason)
SELECT id,1,true,'system',CASE module_id
 WHEN 'audit-console' THEN 'Enable audit console in the platform baseline'
 WHEN 'feedback' THEN 'Enable feedback utility in the platform baseline'
 WHEN 'sensitive-words' THEN 'Enable sensitive-word policy in the platform baseline'
 WHEN 'localization' THEN 'Enable runtime localization in the platform baseline'
END
FROM platform_module_state s
WHERE module_id IN ('audit-console','feedback','sensitive-words','localization')
AND NOT EXISTS (SELECT 1 FROM platform_module_revision r WHERE r.module_state_id=s.id AND r.version=1);

INSERT INTO platform_audit(id,actor,action,target_id)
SELECT gen_random_uuid(),'system','module.enabled',s.id
FROM platform_module_state s
WHERE s.module_id IN ('audit-console','feedback','sensitive-words','localization')
AND NOT EXISTS (SELECT 1 FROM platform_audit a WHERE a.actor='system' AND a.action='module.enabled' AND a.target_id=s.id);

-- Stable dictionary ids make profile bindings and seed diffs deterministic.
INSERT INTO platform_dictionary(id,code,name,remark) VALUES
 ('00000000-0000-0000-0000-000000000201','work_order_feedback_status','工单反馈状态','remark=TERMINAL marks handled states'),
 ('00000000-0000-0000-0000-000000000202','work_order_feedback_priority','工单反馈优先级','Work order feedback priorities'),
 ('00000000-0000-0000-0000-000000000203','work_order_feedback_default','工单反馈默认配置','value=setting key, label=setting value')
ON CONFLICT(code) DO NOTHING;

INSERT INTO platform_dictionary_item(id,dictionary_id,value,label,sort_order,remark)
SELECT seed.id::uuid,d.id,seed.value,seed.label,seed.sort_order::integer,seed.remark
FROM (VALUES
 ('00000000-0000-0000-0000-000000000301','work_order_feedback_status','OPEN','待处理',10,''),
 ('00000000-0000-0000-0000-000000000302','work_order_feedback_status','PROCESSING','处理中',20,''),
 ('00000000-0000-0000-0000-000000000303','work_order_feedback_status','RESOLVED','已解决',30,'TERMINAL'),
 ('00000000-0000-0000-0000-000000000304','work_order_feedback_status','CLOSED','已关闭',40,'TERMINAL'),
 ('00000000-0000-0000-0000-000000000305','work_order_feedback_priority','LOW','低',10,''),
 ('00000000-0000-0000-0000-000000000306','work_order_feedback_priority','NORMAL','普通',20,''),
 ('00000000-0000-0000-0000-000000000307','work_order_feedback_priority','HIGH','高',30,''),
 ('00000000-0000-0000-0000-000000000308','work_order_feedback_priority','URGENT','紧急',40,''),
 ('00000000-0000-0000-0000-000000000309','work_order_feedback_default','INITIAL_STATUS','OPEN',10,''),
 ('00000000-0000-0000-0000-000000000310','work_order_feedback_default','DEFAULT_PRIORITY','NORMAL',20,''),
 ('00000000-0000-0000-0000-000000000311','work_order_feedback_default','UPLOAD_BUCKET','support_feedback',30,''),
 ('00000000-0000-0000-0000-000000000312','work_order_feedback_default','IMAGE_CATEGORY','工单反馈',40,''),
 ('00000000-0000-0000-0000-000000000313','work_order_feedback_default','IMAGE_REMARK','工单反馈富文本图片',50,'')
) AS seed(id,code,value,label,sort_order,remark)
JOIN platform_dictionary d ON d.code=seed.code
ON CONFLICT(dictionary_id,value) DO NOTHING;

-- Server-owned navigation is shared by tree and dual navigation.  CATALOG rows
-- provide grouping only; PAGE rows carry the user-facing route.
INSERT INTO platform_navigation_menu(id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,permission_key,global_only,module_id,sort_order,built_in)
VALUES
 ('00000000-0000-0000-0000-000000000001',NULL,'overview','overview','概览','CATALOG',NULL,NULL,NULL,false,NULL,10,true),
 ('00000000-0000-0000-0000-000000000002',NULL,'administration','administration','平台管理','CATALOG',NULL,NULL,NULL,false,NULL,20,true),
 ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','system-settings','systemSettings','系统设置','CATALOG',NULL,NULL,NULL,false,NULL,10,true),
 ('00000000-0000-0000-0000-000000000010','00000000-0000-0000-0000-000000000001','home','home','工作台','PAGE','home','/dashboard/home',NULL,false,NULL,10,true),
 ('00000000-0000-0000-0000-000000000011','00000000-0000-0000-0000-000000000001','files','myFiles','我的文件','PAGE','files','/dashboard/files',NULL,false,NULL,20,true),
 ('00000000-0000-0000-0000-000000000020','00000000-0000-0000-0000-000000000002','modules','modules','平台模块','PAGE','modules','/dashboard/modules','platform:modules:read',false,NULL,10,true),
 ('00000000-0000-0000-0000-000000000021','00000000-0000-0000-0000-000000000002','monitoring','monitoring','运行监控','PAGE','monitoring','/dashboard/monitoring','platform:audit:read',false,NULL,20,true),
 ('00000000-0000-0000-0000-000000000022','00000000-0000-0000-0000-000000000002','users','users','用户管理','PAGE','users','/dashboard/users','platform:users:read',false,NULL,30,true),
 ('00000000-0000-0000-0000-000000000023','00000000-0000-0000-0000-000000000002','online-users','onlineUsers','在线用户','PAGE','online-users','/dashboard/online-users','platform:users:read',true,NULL,40,true),
 ('00000000-0000-0000-0000-000000000024','00000000-0000-0000-0000-000000000002','roles','roles','角色管理','PAGE','roles','/dashboard/roles','platform:roles:read',false,NULL,50,true),
 ('00000000-0000-0000-0000-000000000025','00000000-0000-0000-0000-000000000002','organizations','organizations','组织管理','PAGE','organizations','/dashboard/organizations','platform:organizations:read',false,NULL,60,true),
 ('00000000-0000-0000-0000-000000000026','00000000-0000-0000-0000-000000000002','dictionaries','dictionaries','字典管理','PAGE','dictionaries','/dashboard/dictionaries','platform:dictionaries:read',false,NULL,70,true),
 ('00000000-0000-0000-0000-000000000050','00000000-0000-0000-0000-000000000003','files-all','allFiles','全部文件','PAGE','files-all','/dashboard/files-all','platform:files:read',true,NULL,75,true),
 ('00000000-0000-0000-0000-000000000051','00000000-0000-0000-0000-000000000003','notifications','notificationCenter','通知中心','PAGE','notifications','/dashboard/notifications','platform:notifications:read',true,NULL,85,true),
 ('00000000-0000-0000-0000-000000000027','00000000-0000-0000-0000-000000000003','branding','branding','品牌设置','PAGE','branding','/dashboard/branding','platform:settings:read',false,NULL,10,true),
 ('00000000-0000-0000-0000-000000000028','00000000-0000-0000-0000-000000000003','smtp','smtpSettings','邮件服务器','PAGE','smtp','/dashboard/smtp','platform:settings:read',false,NULL,20,true),
 ('00000000-0000-0000-0000-000000000029','00000000-0000-0000-0000-000000000003','profile-fields','profileFields','资料字段','PAGE','profile-fields','/dashboard/profile-fields','platform:settings:read',false,NULL,30,true),
 ('00000000-0000-0000-0000-000000000030','00000000-0000-0000-0000-000000000003','agreements','agreements','协议设置','PAGE','agreements','/dashboard/agreements','platform:settings:read',false,NULL,40,true),
 ('00000000-0000-0000-0000-000000000031','00000000-0000-0000-0000-000000000003','watermark','watermark','水印设置','PAGE','watermark','/dashboard/watermark','platform:settings:read',false,NULL,50,true),
 ('00000000-0000-0000-0000-000000000032','00000000-0000-0000-0000-000000000003','maintenance','maintenance','维护设置','PAGE','maintenance','/dashboard/maintenance','platform:settings:read',false,NULL,60,true),
 ('00000000-0000-0000-0000-000000000049','00000000-0000-0000-0000-000000000003','security','securitySettings','安全策略','PAGE','security','/dashboard/security','platform:settings:read',false,NULL,65,true),
 ('00000000-0000-0000-0000-000000000052','00000000-0000-0000-0000-000000000003','passkey','passkeySettings','通行密钥设置','PAGE','passkey','/dashboard/passkey','platform:settings:read',false,NULL,67,true),
 ('00000000-0000-0000-0000-000000000033','00000000-0000-0000-0000-000000000002','audit','audit','审计查询','PAGE','audit','/dashboard/audit','platform:audit:read',false,'audit-console',80,true),
 ('00000000-0000-0000-0000-000000000034','00000000-0000-0000-0000-000000000002','mail-deliveries','mailDeliveries','邮件投递','PAGE','mail-deliveries','/dashboard/mail-deliveries','platform:audit:read',false,NULL,90,true),
 ('00000000-0000-0000-0000-000000000035','00000000-0000-0000-0000-000000000002','event-outbox','eventOutbox','事件 Outbox','PAGE','event-outbox','/dashboard/event-outbox','platform:audit:read',false,NULL,100,true),
 ('00000000-0000-0000-0000-000000000036','00000000-0000-0000-0000-000000000002','feedback','feedback','意见反馈','PAGE','feedback','/dashboard/feedback','platform:feedback:read',false,'feedback',110,true),
 ('00000000-0000-0000-0000-000000000037','00000000-0000-0000-0000-000000000002','navigation','navigation','导航管理','PAGE','navigation','/dashboard/navigation','platform:navigation:read',false,NULL,120,true),
 ('00000000-0000-0000-0000-000000000038','00000000-0000-0000-0000-000000000003','sensitive-words','sensitiveWords','敏感词策略','PAGE','sensitive-words','/dashboard/sensitive-words','platform:sensitive-words:read',true,'sensitive-words',70,true),
 ('00000000-0000-0000-0000-000000000039','00000000-0000-0000-0000-000000000003','localization','localizationManagement','本地化管理','PAGE','localization','/dashboard/localization','platform:localization:read',true,'localization',80,true),
 ('00000000-0000-0000-0000-000000000047','00000000-0000-0000-0000-000000000003','sms','smsSettings','短信服务','PAGE','sms','/dashboard/sms','platform:settings:read',false,NULL,25,true),
 ('00000000-0000-0000-0000-000000000048','00000000-0000-0000-0000-000000000002','sms-deliveries','smsDeliveries','短信投递','PAGE','sms-deliveries','/dashboard/sms-deliveries','platform:audit:read',false,NULL,95,true)
ON CONFLICT(menu_code) DO NOTHING;

-- The baseline carries no administrator password.  Bootstrap grants are applied
-- by IdentityConfiguration once the configured password creates the admin row.
