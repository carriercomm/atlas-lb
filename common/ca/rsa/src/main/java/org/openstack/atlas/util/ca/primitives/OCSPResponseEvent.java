package org.openstack.atlas.util.ca.primitives;

@Deprecated
public enum OCSPResponseEvent {

    NO_OCSP_URI_FOUND,
    ERROR_BUILDING_OCSP_REQUEST,
    USER_CRT_WAS_NULL,
    OCSP_URI_MALFORMED,
    IO_EXCEPTION_OPENING_URL,
    CANT_HANDLE_SCHEMA_ON_OCSP_URI,
    IO_EXCEPTION_SENDING_REQUEST,
    BAD_HTTP_STATUS_CODE_CALLING_OSCP_URI

}
