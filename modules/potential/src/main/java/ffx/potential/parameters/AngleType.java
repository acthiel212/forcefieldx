// ******************************************************************************
//
// Title:       Force Field X.
// Description: Force Field X - Software for Molecular Biophysics.
// Copyright:   Copyright (c) Michael J. Schnieders 2001-2025.
//
// This file is part of Force Field X.
//
// Force Field X is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 3 as published by
// the Free Software Foundation.
//
// Force Field X is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
// details.
//
// You should have received a copy of the GNU General Public License along with
// Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
// Place, Suite 330, Boston, MA 02111-1307 USA
//
// Linking this library statically or dynamically with other modules is making a
// combined work based on this library. Thus, the terms and conditions of the
// GNU General Public License cover the whole combination.
//
// As a special exception, the copyright holders of this library give you
// permission to link this library with independent modules to produce an
// executable, regardless of the license terms of these independent modules, and
// to copy and distribute the resulting executable under terms of your choice,
// provided that you also meet, for each linked independent module, the terms
// and conditions of the license of that module. An independent module is a
// module which is not derived from or based on this library. If you modify this
// library, you may extend this exception to your version of the library, but
// you are not obligated to do so. If you do not wish to do so, delete this
// exception statement from your version.
//
// ******************************************************************************
package ffx.potential.parameters;

import ffx.utilities.FFXProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ffx.potential.parameters.ForceField.ForceFieldType.ANGLE;
import static ffx.potential.parameters.ForceField.ForceFieldType.ANGLEP;
import static ffx.utilities.Constants.DEGREES_PER_RADIAN;
import static ffx.utilities.Constants.KCAL_TO_KJ;
import static ffx.utilities.PropertyGroup.EnergyUnitConversion;
import static ffx.utilities.PropertyGroup.LocalGeometryFunctionalForm;
import static ffx.utilities.PropertyGroup.PotentialFunctionParameter;
import static java.lang.Double.parseDouble;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.lang.System.arraycopy;
import static org.apache.commons.math3.util.FastMath.PI;
import static org.apache.commons.math3.util.FastMath.pow;

/**
 * The AngleType class defines one harmonic angle bend energy term.
 *
 * @author Michael J. Schnieders
 * @since 1.0
 */
@FFXProperty(name = "angle", clazz = String.class, propertyGroup = PotentialFunctionParameter, description = """
    [3 integers and 4 reals]
    Provides the values for a single bond angle bending parameter.
    The integer modifiers give the atom class numbers for the three kinds of atoms involved in the angle which is to be defined.
    The real number modifiers give the force constant value for the angle and up to three ideal bond angles in degrees.
    In most cases only one ideal bond angle is given, and that value is used for all occurrences of the specified bond angle.
    If all three ideal angles are given, the values apply when the central atom of the angle is attached to 0, 1 or 2 additional hydrogen atoms, respectively.
    This "hydrogen environment" option is provided to implement the corresponding feature of the AMOEBA force field.
    The default units for the force constant are kcal/mole/radian^2, but this can be controlled via the angleunit keyword.
    """)
@FFXProperty(name = "anglep", clazz = String.class, propertyGroup = PotentialFunctionParameter, description = """
    [3 integers and 3 reals]
    Provides the values for a single projected in-plane bond angle bending parameter.
    The integer modifiers give the atom class numbers for the three kinds of atoms involved in the angle which is to be defined.
    The real number modifiers give the force constant value for the angle and up to two ideal bond angles in degrees.
    In most cases only one ideal bond angle is given, and that value is used for all occurrences of the specified bond angle.
    If all two ideal angles are given, the values apply when the central atom of the angle is attached to 0 or 1 additional hydrogen atoms, respectively.
    This "hydrogen environment" option is provided to implement the corresponding feature of the AMOEBA force field.
    The default units for the force constant are kcal/mole/radian^2, but this can be controlled via the angleunit keyword.
    """)
public final class AngleType extends BaseType implements Comparator<String> {

  /**
   * Default convert angle bending energy to kcal/mole.
   */
  public static final double DEFAULT_ANGLE_UNIT = pow(PI / 180.0, 2.0);
  /**
   * Default cubic coefficient in angle bending potential.
   */
  public static final double DEFAULT_ANGLE_CUBIC = 0.0;
  /**
   * Default quartic coefficient in angle bending potential.
   */
  public static final double DEFAULT_ANGLE_QUARTIC = 0.0;
  /**
   * Default pentic coefficient in angle bending potential.
   */
  public static final double DEFAULT_ANGLE_PENTIC = 0.0;
  /**
   * Default quintic coefficient in angle bending potential.
   */
  public static final double DEFAULT_ANGLE_SEXTIC = 0.0;

  /**
   * Convert angle bending energy to kcal/mole.
   */
  @FFXProperty(name = "angleunit", propertyGroup = EnergyUnitConversion, defaultValue = "(Pi/180)^2", description = """
      Sets the scale factor needed to convert the energy value computed by the bond angle bending potential into units of kcal/mole.
      The correct value is force field dependent and typically provided in the header of the master force field parameter file.
      """)
  public double angleUnit = DEFAULT_ANGLE_UNIT;

  /**
   * Cubic coefficient in angle bending potential.
   */
  @FFXProperty(name = "angle-cubic", propertyGroup = LocalGeometryFunctionalForm, defaultValue = "0.0", description = """
      Sets the value of the cubic term in the Taylor series expansion form of the bond angle bending potential energy.
      The real number modifier gives the value of the coefficient as a multiple of the quadratic coefficient.
      This term multiplied by the angle bending energy unit conversion factor, the force constant,
      and the cube of the deviation of the bond angle from its ideal value gives the cubic contribution to the angle bending energy.
      The default value in the absence of the angle-cubic keyword is zero; i.e., the cubic angle bending term is omitted.
      """)
  public double cubic = DEFAULT_ANGLE_CUBIC;

  /**
   * Quartic coefficient in angle bending potential.
   */
  @FFXProperty(name = "angle-quartic", propertyGroup = LocalGeometryFunctionalForm, defaultValue = "0.0", description = """
      Sets the value of the quartic term in the Taylor series expansion form of the bond angle bending potential energy.
      The real number modifier gives the value of the coefficient as a multiple of the quadratic coefficient.
      This term multiplied by the angle bending energy unit conversion factor, the force constant,
      and the forth power of the deviation of the bond angle from its ideal value gives the quartic contribution to the angle bending energy.
      The default value in the absence of the angle-quartic keyword is zero; i.e., the quartic angle bending term is omitted.
      """)
  public double quartic = DEFAULT_ANGLE_QUARTIC;

  /**
   * Pentic coefficient in angle bending potential.
   */
  @FFXProperty(name = "angle-pentic", propertyGroup = LocalGeometryFunctionalForm, defaultValue = "0.0", description = """
      Sets the value of the fifth power term in the Taylor series expansion form of the bond angle bending potential energy.
      The real number modifier gives the value of the coefficient as a multiple of the quadratic coefficient.
      This term multiplied by the angle bending energy unit conversion factor, the force constant,
      and the fifth power of the deviation of the bond angle from its ideal value gives the pentic contribution to the angle bending energy.
      The default value in the absence of the angle-pentic keyword is zero; i.e., the pentic angle bending term is omitted.
      """)
  public double pentic = DEFAULT_ANGLE_PENTIC;

  /**
   * Sextic coefficient in angle bending potential.
   */
  @FFXProperty(name = "angle-sextic", propertyGroup = LocalGeometryFunctionalForm, defaultValue = "0.0", description = """
      Sets the value of the sixth power term in the Taylor series expansion form of the bond angle bending potential energy.
      The real number modifier gives the value of the coefficient as a multiple of the quadratic coefficient.
      This term multiplied by the angle bending energy unit conversion factor, the force constant,
      and the sixth power of the deviation of the bond angle from its ideal value gives the sextic contribution to the angle bending energy.
      The default value in the absence of the angle-sextic keyword is zero; i.e., the sextic angle bending term is omitted.
      """)
  public double sextic = DEFAULT_ANGLE_SEXTIC;

  /**
   * A Logger for the AngleType class.
   */
  private static final Logger logger = Logger.getLogger(AngleType.class.getName());
  /**
   * Atom classes that for this Angle type.
   */
  public final int[] atomClasses;
  /**
   * Force constant (Kcal/mole/radian^2).
   */
  public final double forceConstant;
  /**
   * Equilibrium angle (degrees). There can be up to three equilibrium angles, depending on the
   * number of attached hydrogens (0, 1, or 2).
   */
  public final double[] angle;
  /**
   * The angle mode in use.
   */
  public final AngleMode angleMode;
  /**
   * The angle function in use.
   */
  public AngleFunction angleFunction;

  /**
   * The default AngleType constructor defines use of the Sextic AngleFunction.
   *
   * @param atomClasses   an array of int.
   * @param forceConstant a double.
   * @param angle         an array of double.
   */
  public AngleType(int[] atomClasses, double forceConstant, double[] angle) {
    this(atomClasses, forceConstant, angle, AngleMode.NORMAL);
  }

  /**
   * Constructor for In-Plane AngleType.
   *
   * @param atomClasses   an array of int.
   * @param forceConstant a double.
   * @param angle         an array of double.
   * @param angleMode     the AngleMode to apply.
   */
  public AngleType(int[] atomClasses, double forceConstant, double[] angle, AngleMode angleMode) {
    this(atomClasses, forceConstant, angle, angleMode, AngleFunction.SEXTIC);
  }

  /**
   * Constructor for In-Plane AngleType.
   *
   * @param atomClasses   an array of int.
   * @param forceConstant a double.
   * @param angle         an array of double.
   * @param angleMode     the AngleMode to apply.
   * @param angleFunction the AngleFunction to use.
   */
  public AngleType(int[] atomClasses, double forceConstant, double[] angle, AngleMode angleMode,
                   AngleFunction angleFunction) {
    super(ANGLE, sortKey(atomClasses));
    this.atomClasses = atomClasses;
    this.forceConstant = forceConstant;
    this.angle = angle;
    this.angleMode = angleMode;
    this.angleFunction = angleFunction;
    if (angleMode == AngleMode.IN_PLANE) {
      forceFieldType = ANGLEP;
    }
  }

  /**
   * Average two AngleType instances. The atom classes that define the new type must be supplied.
   *
   * @param angleType1  a {@link ffx.potential.parameters.AngleType} object.
   * @param angleType2  a {@link ffx.potential.parameters.AngleType} object.
   * @param atomClasses an array of {@link int} objects.
   * @return a {@link ffx.potential.parameters.AngleType} object.
   */
  public static AngleType average(AngleType angleType1, AngleType angleType2, int[] atomClasses) {
    if (angleType1 == null || angleType2 == null || atomClasses == null) {
      return null;
    }
    AngleMode angleMode = angleType1.angleMode;
    if (angleMode != angleType2.angleMode) {
      return null;
    }
    AngleFunction angleFunction = angleType1.angleFunction;
    if (angleFunction != angleType2.angleFunction) {
      return null;
    }
    int len = angleType1.angle.length;
    if (len != angleType2.angle.length) {
      return null;
    }
    double forceConstant = (angleType1.forceConstant + angleType2.forceConstant) / 2.0;
    double[] angle = new double[len];
    for (int i = 0; i < len; i++) {
      angle[i] = (angleType1.angle[i] + angleType2.angle[i]) / 2.0;
    }

    return new AngleType(atomClasses, forceConstant, angle, angleMode, angleFunction);
  }

  /**
   * Construct an AngleType from an input string.
   *
   * @param input  The overall input String.
   * @param tokens The input String tokenized.
   * @return an AngleType instance.
   */
  public static AngleType parse(String input, String[] tokens) {
    if (tokens.length < 6) {
      logger.log(Level.WARNING, "Invalid ANGLE type:\n{0}", input);
    } else {
      int[] atomClasses = new int[3];
      double forceConstant;
      int angles;
      double[] bondAngle;
      try {
        atomClasses[0] = parseInt(tokens[1]);
        atomClasses[1] = parseInt(tokens[2]);
        atomClasses[2] = parseInt(tokens[3]);
        forceConstant = parseDouble(tokens[4]);
        angles = tokens.length - 5;
        bondAngle = new double[angles];
        for (int i = 0; i < angles; i++) {
          bondAngle[i] = parseDouble(tokens[5 + i]);
        }
      } catch (NumberFormatException e) {
        String message = "Exception parsing ANGLE type:\n" + input + "\n";
        logger.log(Level.SEVERE, message, e);
        return null;
      }
      double[] newBondAngle = new double[angles];
      arraycopy(bondAngle, 0, newBondAngle, 0, angles);
      return new AngleType(atomClasses, forceConstant, newBondAngle);
    }
    return null;
  }

  /**
   * Construct an In-Plane AngleType from an input string.
   *
   * @param input  The overall input String.
   * @param tokens The input String tokenized.
   * @return an AngleType instance.
   */
  public static AngleType parseInPlane(String input, String[] tokens) {
    if (tokens.length < 6) {
      logger.log(Level.WARNING, "Invalid ANGLEP type:\n{0}", input);
    } else {
      int[] atomClasses = new int[3];
      double forceConstant;
      int angles;
      double[] bondAngle;
      try {
        atomClasses[0] = parseInt(tokens[1]);
        atomClasses[1] = parseInt(tokens[2]);
        atomClasses[2] = parseInt(tokens[3]);
        forceConstant = parseDouble(tokens[4]);
        angles = tokens.length - 5;
        bondAngle = new double[angles];
        for (int i = 0; i < angles; i++) {
          bondAngle[i] = parseDouble(tokens[5 + i]);
        }
      } catch (NumberFormatException e) {
        String message = "Exception parsing ANGLEP type:\n" + input + "\n";
        logger.log(Level.SEVERE, message, e);
        return null;
      }
      double[] newBondAngle = new double[angles];
      arraycopy(bondAngle, 0, newBondAngle, 0, angles);
      return new AngleType(atomClasses, forceConstant, newBondAngle, AngleMode.IN_PLANE);
    }
    return null;
  }

  /**
   * This method sorts the atom classes as: min, c[1], max
   *
   * @param c atomClasses
   * @return lookup key
   */
  public static String sortKey(int[] c) {
    if (c == null || c.length != 3) {
      return null;
    }
    if (c[0] > c[2]) {
      int temp = c[0];
      c[0] = c[2];
      c[2] = temp;
    }
    return c[0] + " " + c[1] + " " + c[2];
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int compare(String key1, String key2) {
    String[] keys1 = key1.split(" ");
    String[] keys2 = key2.split(" ");
    int[] c1 = new int[3];
    int[] c2 = new int[3];
    for (int i = 0; i < 3; i++) {
      c1[i] = Integer.parseInt(keys1[i]);
      c2[i] = Integer.parseInt(keys2[i]);
    }

    if (c1[1] < c2[1]) {
      return -1;
    } else if (c1[1] > c2[1]) {
      return 1;
    } else if (c1[0] < c2[0]) {
      return -1;
    } else if (c1[0] > c2[0]) {
      return 1;
    } else if (c1[2] < c2[2]) {
      return -1;
    } else if (c1[2] > c2[2]) {
      return 1;
    }

    return 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AngleType angleType = (AngleType) o;
    return Arrays.equals(atomClasses, angleType.atomClasses);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int hashCode() {
    return Arrays.hashCode(atomClasses);
  }

  /**
   * incrementClasses
   *
   * @param increment the value to add to each atom class.
   */
  public void incrementClasses(int increment) {
    for (int i = 0; i < atomClasses.length; i++) {
      atomClasses[i] += increment;
    }
    setKey(sortKey(atomClasses));
  }

  /**
   * Remap new atom classes to known internal ones.
   *
   * @param typeMap a lookup between new atom types and known atom types.
   * @return a {@link ffx.potential.parameters.AngleType} object.
   */
  public AngleType patchClasses(HashMap<AtomType, AtomType> typeMap) {
    int count = 0;
    int len = atomClasses.length;

    // Look for new AngleTypes that contain 1 or 2 mapped atom classes.
    for (AtomType newType : typeMap.keySet()) {
      for (int atomClass : atomClasses) {
        if (atomClass == newType.atomClass) {
          count++;
        }
      }
    }

    // If found, create a new AngleType that bridges to known classes.
    if (count == 1 || count == 2) {
      int[] newClasses = Arrays.copyOf(atomClasses, len);
      for (AtomType newType : typeMap.keySet()) {
        for (int i = 0; i < len; i++) {
          if (atomClasses[i] == newType.atomClass) {
            AtomType knownType = typeMap.get(newType);
            newClasses[i] = knownType.atomClass;
          }
        }
      }
      return new AngleType(newClasses, forceConstant, angle, angleMode, angleFunction);
    }

    return null;
  }

  /**
   * Set the AngleFunction.
   *
   * @param angleFunction the AngleFunction.
   */
  public void setAngleFunction(AngleFunction angleFunction) {
    this.angleFunction = angleFunction;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Nicely formatted Angle bending string.
   */
  @Override
  public String toString() {
    StringBuilder angleString;
    if (angleMode == AngleMode.NORMAL) {
      angleString = new StringBuilder(
          format("angle  %5d  %5d  %5d  %6.2f", atomClasses[0], atomClasses[1], atomClasses[2],
              forceConstant));
    } else {
      angleString = new StringBuilder(
          format("anglep  %5d  %5d  %5d  %6.2f", atomClasses[0], atomClasses[1], atomClasses[2],
              forceConstant));
    }
    for (double eq : angle) {
      angleString.append(format("  %6.2f", eq));
    }
    return angleString.toString();
  }

  /**
   * Create an AmoebaAngleForce
   *
   * @param doc        the Document instance.
   * @param forceField the ForceField to define constants from.
   * @return the AmoebaAngleForce Element.
   */
  public static Element getXMLForce(Document doc, ForceField forceField) {
    Map<String, AngleType> angMap = forceField.getAngleTypes();
    angMap.putAll(forceField.getAnglepTypes());
    if (!angMap.values().isEmpty()) {
      Element node = doc.createElement("AmoebaAngleForce");
      node.setAttribute("angle-cubic", valueOf(forceField.getDouble("angle-cubic", DEFAULT_ANGLE_CUBIC)));
      node.setAttribute("angle-quartic", valueOf(forceField.getDouble("angle-quartic", DEFAULT_ANGLE_QUARTIC)));
      node.setAttribute("angle-pentic", valueOf(forceField.getDouble("angle-pentic", DEFAULT_ANGLE_PENTIC)));
      node.setAttribute("angle-sextic", valueOf(forceField.getDouble("angle-sextic", DEFAULT_ANGLE_SEXTIC)));
      for (AngleType angleType : angMap.values()) {
        node.appendChild(angleType.toXML(doc));
      }
      return node;
    }
    return null;
  }

  /**
   * Write AngleType to OpenMM XML format.
   *
   * @param doc the Document instance.
   * @return the Angle element.
   */
  public Element toXML(Document doc) {
    Element node = doc.createElement("Angle");
    node.setAttribute("class1", format("%d", atomClasses[0]));
    node.setAttribute("class2", format("%d", atomClasses[1]));
    node.setAttribute("class3", format("%d", atomClasses[2]));
    // Convert Kcal/mol/radian^2 to KJ/mol/deg^2
    node.setAttribute("k", format("%.17f", forceConstant * KCAL_TO_KJ / (DEGREES_PER_RADIAN * DEGREES_PER_RADIAN)));
    int i = 1;
    for (double eq : angle) {
      node.setAttribute(format("angle%d", i), format("%f", eq));
      i++;
    }
    if (angleMode == AngleMode.NORMAL) {
      node.setAttribute("inPlane", "False");
    } else {
      node.setAttribute("inPlane", "True");
    }
    return node;
  }

  /**
   * Angle function types include harmonic or sextic.
   */
  public enum AngleFunction {
    HARMONIC, SEXTIC
  }

  /**
   * Angle modes include Normal or In-Plane
   */
  public enum AngleMode {
    NORMAL, IN_PLANE
  }
}
