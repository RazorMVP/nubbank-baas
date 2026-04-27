package com.nubbank.baas.ncube.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CbnApiResponse<T>(T Data, CbnLinks Links, CbnMeta Meta) {}
