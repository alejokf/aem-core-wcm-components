/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Copyright 2019 Adobe
 ~
 ~ Licensed under the Apache License, Version 2.0 (the "License");
 ~ you may not use this file except in compliance with the License.
 ~ You may obtain a copy of the License at
 ~
 ~     http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing, software
 ~ distributed under the License is distributed on an "AS IS" BASIS,
 ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ~ See the License for the specific language governing permissions and
 ~ limitations under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/
package com.adobe.cq.wcm.core.components.internal.models.v1;

import java.util.Calendar;
import javax.annotation.Nonnull;
import javax.annotation.PostConstruct;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.commons.mime.MimeTypeService;
import org.apache.sling.models.annotations.Exporter;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.InjectionStrategy;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.ScriptVariable;
import org.apache.sling.models.annotations.injectorspecific.Self;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;

import com.adobe.cq.export.json.ComponentExporter;
import com.adobe.cq.export.json.ExporterConstants;
import com.adobe.cq.wcm.core.components.commons.link.Link;
import com.adobe.cq.wcm.core.components.internal.link.LinkHandler;
import com.adobe.cq.wcm.core.components.internal.servlets.DownloadServlet;
import com.adobe.cq.wcm.core.components.models.Download;
import com.day.cq.commons.DownloadResource;
import com.day.cq.commons.jcr.JcrConstants;
import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.DamConstants;
import com.day.cq.wcm.api.designer.Style;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Model(adaptables = SlingHttpServletRequest.class,
       adapters = {Download.class, ComponentExporter.class},
       resourceType = DownloadImpl.RESOURCE_TYPE)
@Exporter(name = ExporterConstants.SLING_MODEL_EXPORTER_NAME,
          extensions = ExporterConstants.SLING_MODEL_EXTENSION)
public class DownloadImpl implements Download {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadImpl.class);

    public final static String RESOURCE_TYPE = "core/wcm/components/download/v1/download";

    @Self
    private SlingHttpServletRequest request;

    @Self
    private LinkHandler linkHandler;

    @ScriptVariable
    private Resource resource;

    @OSGiService
    private MimeTypeService mimeTypeService;

    @ScriptVariable
    private ValueMap properties;

    @ScriptVariable(injectionStrategy = InjectionStrategy.OPTIONAL)
    @JsonIgnore
    @Nullable
    protected Style currentStyle;

    @SlingObject
    private ResourceResolver resourceResolver;

    private String url;

    private boolean titleFromAsset = false;

    private boolean descriptionFromAsset = false;

    private boolean inline = false;

    private boolean displaySize;

    private boolean displayFormat;

    private boolean displayFilename;

    @ValueMapValue(injectionStrategy = InjectionStrategy.OPTIONAL, name = JcrConstants.JCR_TITLE)
    @Nullable
    private String title;

    @ValueMapValue(injectionStrategy = InjectionStrategy.OPTIONAL, name = JcrConstants.JCR_DESCRIPTION)
    @Nullable
    private String description;

    @ValueMapValue(injectionStrategy = InjectionStrategy.OPTIONAL)
    @Nullable
    private String actionText;

    private String titleType;

    private String filename;

    private String format;

    private String size;

    private String extension;

    private long lastModified = 0;

    @PostConstruct
    protected void initModel() {
        String fileReference = properties.get(DownloadResource.PN_REFERENCE, String.class);
        titleFromAsset = properties.get(PN_TITLE_FROM_ASSET, titleFromAsset);
        descriptionFromAsset = properties.get(PN_DESCRIPTION_FROM_ASSET, descriptionFromAsset);
        inline = properties.get(PN_INLINE, inline);
        if (currentStyle != null) {
            titleType = currentStyle.get(PN_TITLE_TYPE, String.class);
            displaySize = currentStyle.get(PN_DISPLAY_SIZE, true);
            displayFormat = currentStyle.get(PN_DISPLAY_FORMAT, true);
            displayFilename = currentStyle.get(PN_DISPLAY_FILENAME, true);
        }
        if (StringUtils.isNotBlank(fileReference)) {
            initAssetDownload(fileReference);
        } else {
            Resource file = resource.getChild(DownloadResource.NN_FILE);
            if (file != null) {
                initFileDownload(file);
            }
        }
    }

    private void initFileDownload(Resource file) {
        filename = properties.get(DownloadResource.PN_FILE_NAME, String.class);
        if (StringUtils.isNotEmpty(filename)) {
            Resource fileContent = file.getChild(JcrConstants.JCR_CONTENT);
            if (fileContent != null) {
                ValueMap valueMap = fileContent.adaptTo(ValueMap.class);
                if (valueMap != null) {
                    format = valueMap.get(JcrConstants.JCR_MIMETYPE, String.class);

                    if (StringUtils.isNotEmpty(format)) {
                        extension = mimeTypeService.getExtension(format);
                    }
                    Calendar calendar = valueMap.get(JcrConstants.JCR_LASTMODIFIED, Calendar.class);
                    if (calendar != null) {
                        lastModified = calendar.getTimeInMillis();
                    }
                    if (StringUtils.isNotEmpty(format)) {
                        extension = mimeTypeService.getExtension(format);
                    }

                    url = linkHandler.getLink(getDownloadUrl(file) + "/" + filename, null).map(Link::getURL).orElse(null);
                    size = FileUtils.byteCountToDisplaySize(getFileSize(fileContent));
                }
            }
        }
    }

    private void initAssetDownload(String fileReference) {
            Resource downloadResource = resourceResolver.getResource(fileReference);
            if (downloadResource != null) {
                Asset downloadAsset = downloadResource.adaptTo(Asset.class);
                if (downloadAsset != null) {
                    Calendar resourceLastModified = properties.get(JcrConstants.JCR_LASTMODIFIED, Calendar.class);
                    if (resourceLastModified != null) {
                        lastModified = resourceLastModified.getTimeInMillis();
                    }
                    long assetLastModified = downloadAsset.getLastModified();
                    if (assetLastModified > lastModified) {
                        lastModified = assetLastModified;
                    }

                    filename = downloadAsset.getName();

                    format = downloadAsset.getMetadataValue(DamConstants.DC_FORMAT);

                    if (StringUtils.isNoneEmpty(format)) {
                        extension = mimeTypeService.getExtension(format);
                    }

                    if (StringUtils.isEmpty(extension)) {
                        extension = FilenameUtils.getExtension(filename);
                    }

                    url = linkHandler.getLink(getDownloadUrl(downloadResource), null).map(Link::getURL).orElse(null);

                    if (titleFromAsset) {
                        title = downloadAsset.getMetadataValue(DamConstants.DC_TITLE);
                    }
                    if (descriptionFromAsset) {
                        String assetDescription = downloadAsset.getMetadataValue(DamConstants.DC_DESCRIPTION);
                        if (StringUtils.isNotBlank(assetDescription)) {
                            description = assetDescription;
                        }
                    }

                    long rawFileSize;
                    Object rawFileSizeObject = downloadAsset.getMetadata(DamConstants.DAM_SIZE);

                    if (rawFileSizeObject != null) {
                        rawFileSize = (Long) rawFileSizeObject;
                    } else {
                        rawFileSize = downloadAsset.getOriginal().getSize();
                    }

                    size = FileUtils.byteCountToDisplaySize(rawFileSize);
            }
        }
    }

    @Nonnull
    @Override
    public String getExportedType() {
        return request.getResource().getResourceType();
    }

    @Override
    public String getUrl() {
        return url;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getActionText() {
        return actionText;
    }

    @Override
    public String getTitleType() {
        return titleType;
    }

    @Override
    public String getFilename() {
        return filename;
    }

    @Override
    public String getFormat() {
        return format;
    }

    @Override
    public String getSize() {
        return size;
    }

    @Override
    public boolean displaySize() {
        return displaySize;
    }

    @Override
    public boolean displayFormat() {
        return displayFormat;
    }

    @Override
    public boolean displayFilename() {
        return displayFilename;
    }

    @Override
    public String getExtension() {
        return extension;
    }

    private long getFileSize(Resource resource) {
        long size = 0;
        Node node = resource.adaptTo(Node.class);
        if (node != null) {
            try {
                Property data = node.getProperty(JcrConstants.JCR_DATA);
                size = data.getBinary().getSize();
            }
            catch (RepositoryException ex) {
                LOGGER.error("Unable to detect binary file size for " + resource.getPath(), ex);
            }
        }
        return size;
    }

    @NotNull
    private String getDownloadUrl(Resource resource) {
        StringBuilder downloadUrlBuilder = new StringBuilder();
        downloadUrlBuilder.append(resource.getPath())
                .append(".")
                .append(DownloadServlet.SELECTOR)
                .append(".");
        if (inline) {
            downloadUrlBuilder.append(DownloadServlet.INLINE_SELECTOR)
                    .append(".");
        }
        downloadUrlBuilder.append(extension);
        return downloadUrlBuilder.toString();
    }
}
