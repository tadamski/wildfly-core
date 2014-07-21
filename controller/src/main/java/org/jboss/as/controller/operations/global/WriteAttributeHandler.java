/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller.operations.global;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ATTRIBUTE_VALUE_WRITTEN_NOTIFICATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.operations.global.GlobalOperationAttributes.NAME;
import static org.jboss.as.controller.operations.global.GlobalOperationAttributes.VALUE;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationDefinition;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.access.AuthorizationResult;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.descriptions.common.ControllerResolver;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.notification.Notification;
import org.jboss.as.controller.operations.validation.ParametersValidator;
import org.jboss.as.controller.operations.validation.StringLengthValidator;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.dmr.ModelNode;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * {@link org.jboss.as.controller.OperationStepHandler} writing a single attribute. The required request parameter "name" represents the attribute name.
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 */
public class WriteAttributeHandler implements OperationStepHandler {

    public static final OperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder(ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION, ControllerResolver.getResolver("global"))
            .setParameters(NAME, VALUE)
            .setRuntimeOnly()
            .build();

    public static final OperationStepHandler INSTANCE = new WriteAttributeHandler();

    private ParametersValidator nameValidator = new ParametersValidator();

    WriteAttributeHandler() {
        nameValidator.registerValidator(NAME.getName(), new StringLengthValidator(1));
    }

    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
        nameValidator.validate(operation);
        final String attributeName = operation.require(NAME.getName()).asString();
        final ImmutableManagementResourceRegistration registry = context.getResourceRegistration();
        if (registry == null) {
            throw new OperationFailedException(ControllerLogger.ROOT_LOGGER.noSuchResourceType(PathAddress.pathAddress(operation.get(OP_ADDR))));
        }
        final AttributeAccess attributeAccess = registry.getAttributeAccess(PathAddress.EMPTY_ADDRESS, attributeName);
        if (attributeAccess == null) {
            throw new OperationFailedException(new ModelNode().set(ControllerLogger.ROOT_LOGGER.unknownAttribute(attributeName)));
        } else if (attributeAccess.getAccessType() != AttributeAccess.AccessType.READ_WRITE) {
            throw new OperationFailedException(new ModelNode().set(ControllerLogger.ROOT_LOGGER.attributeNotWritable(attributeName)));
        } else {

            // Authorize
            ModelNode currentValue;
            if (attributeAccess.getStorageType() == AttributeAccess.Storage.CONFIGURATION) {
                ModelNode model = context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS).getModel();
                currentValue = model.has(attributeName) ? model.get(attributeName) : new ModelNode();
            } else {
                currentValue = new ModelNode();
            }
            AuthorizationResult authorizationResult = context.authorize(operation, attributeName, currentValue);
            if (authorizationResult.getDecision() == AuthorizationResult.Decision.DENY) {
                throw ControllerLogger.ROOT_LOGGER.unauthorized(operation.require(OP).asString(), PathAddress.pathAddress(operation.get(OP_ADDR)), authorizationResult.getExplanation());
            }

            // clone the current value before the model is modified
            final ModelNode oldValue = currentValue.clone();

            OperationStepHandler handler = attributeAccess.getWriteHandler();
            ClassLoader oldTccl = WildFlySecurityManager.setCurrentContextClassLoaderPrivileged(handler.getClass());
            try {
                handler.execute(context, operation);

                ModelNode newValue = context.readResource(PathAddress.EMPTY_ADDRESS).getModel().get(attributeName);
                // only emit a notification if the value has been successfully changed
                if (!oldValue.equals(newValue)) {
                    emitAttributeValueWrittenNotification(context, PathAddress.pathAddress(operation.get(OP_ADDR)), attributeName, oldValue, newValue);
                }

            } finally {
                WildFlySecurityManager.setCurrentContextClassLoaderPrivileged(oldTccl);
            }
        }
    }

    private void emitAttributeValueWrittenNotification(OperationContext context, PathAddress address, String attributeName, ModelNode oldValue, ModelNode newValue) {
        ModelNode data = new ModelNode();
        data.get(NAME.getName()).set(attributeName);
        data.get(GlobalNotifications.OLD_VALUE).set(oldValue);
        data.get(GlobalNotifications.NEW_VALUE).set(newValue);
        Notification notification = new Notification(ATTRIBUTE_VALUE_WRITTEN_NOTIFICATION, address, ControllerLogger.ROOT_LOGGER.attributeValueWritten(attributeName, oldValue, newValue), data);
        context.emit(notification);
    }
}